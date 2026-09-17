package cn.petshop;

import cn.petshop.booking.mapper.AppointmentMapper;
import cn.petshop.booking.service.AppointmentService;
import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.service.MemberService;
import cn.petshop.member.service.PetService;
import cn.petshop.settle.entity.SettleBill;
import cn.petshop.settle.mapper.SettleBillMapper;
import cn.petshop.settle.service.SettleService;
import cn.petshop.wallet.entity.Card;
import cn.petshop.wallet.entity.CardType;
import cn.petshop.wallet.mapper.CardMapper;
import cn.petshop.wallet.mapper.CardTypeMapper;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.CardService;
import cn.petshop.wallet.service.WalletVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 次卡担保结算的收款口径回归（对应审查 P1-3 / P3-1 与「建议补的用例 ①」）。
 *
 * <p>核心不变量：次卡担保冻结的是「一次服务」而不是「一个金额」。
 * 所以 {@code total} 高于担保额时，差额必须有着落（余额补收或现金应收），
 * 绝不能因为操作员在下拉框选了 CASH 就静默漏收；
 * 而 {@code total} 低于担保额时也照常核销 1 次（改价不该让顾客损失次数），不回补现金。
 *
 * <p>同时锁定结算单的 {@code pay_type} 语义：次卡担保的单据固定记 CARD
 * （否则会出现 payType=CASH 而钱来自次卡的矛盾，按渠道对账必错）。
 */
@SpringBootTest
@ActiveProfiles("test")
class CardGuaranteeSettleTest {

    private static final long WASH_SERVICE = 101L;
    private static final LocalTime TWO_PM = LocalTime.of(14, 0);
    private static final String OPERATOR = "店长小爪";
    private static final BigDecimal WASH_PRICE = new BigDecimal("120.00");

    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private SettleService settleService;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private CardService cardService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private PetService petService;
    @Autowired
    private AppointmentMapper apptMapper;
    @Autowired
    private SettleBillMapper settleBillMapper;
    @Autowired
    private CardTypeMapper cardTypeMapper;
    @Autowired
    private CardMapper cardMapper;

    private Member memberWithBalance(BigDecimal amount) {
        Member m = memberService.findOrCreate("mock-card-settle-" + System.nanoTime(), 1L);
        balanceService.recharge(m.getId(), amount, BigDecimal.ZERO, "PY-CS-" + System.nanoTime());
        return m;
    }

    private Pet cat(Member m) {
        Pet pet = new Pet();
        pet.setName("泡芙");
        pet.setType("CAT");
        pet.setHair("SHORT");
        pet.setWeight(new BigDecimal("4.00"));
        return petService.create(m.getId(), pet);
    }

    /** 直接落一张已上架的次卡类型再发卡，绕开后台配置流程（与本用例关注点无关）。 */
    private Card newCard(Member m) {
        CardType type = new CardType();
        type.setStoreId(1L);
        type.setName("洗澡 10 次卡");
        type.setServiceIds("[]");
        type.setSpecIds("[]");
        type.setTimes(10);
        type.setPrice(new BigDecimal("1080.00"));
        type.setValidMode("MONTHS");
        type.setValidMonths(12);
        type.setStatus("ENABLED");
        cardTypeMapper.insert(type);
        return cardService.purchase(m.getId(), type.getId(), "tester");
    }

    /**
     * 次卡担保预约 → 核销签到，返回预约 id。
     * 各用例用不同 dateOffset + 同一 14:00 时段，避免与其它测试类争抢 slot 行。
     */
    private Long arrivedCardAppt(Member m, Pet pet, Card card, int dateOffset) {
        AppointmentService.CreateCmd c = new AppointmentService.CreateCmd();
        c.setServiceId(WASH_SERVICE);
        c.setPetId(pet.getId());
        c.setSlotDate(LocalDate.now().plusDays(dateOffset));
        c.setStartTime(TWO_PM);
        c.setGuaranteeType("CARD");
        c.setCardId(card.getId());
        AppointmentService.ApptVO vo = appointmentService.create(m.getId(), c);
        assertEquals(0, WASH_PRICE.compareTo(vo.getGuaranteeAmount()), "次卡担保额应等于服务价快照");
        appointmentService.checkinByVerifyCode(vo.getVerifyCode());
        return vo.getId();
    }

    private SettleService.ConfirmCmd confirmCmd(String amount, String payType) {
        SettleService.ConfirmItem item = new SettleService.ConfirmItem();
        item.setName("基础洗护套餐");
        item.setCategory("WASH");
        item.setAmount(new BigDecimal(amount));
        SettleService.ConfirmCmd cmd = new SettleService.ConfirmCmd();
        cmd.setItems(List.of(item));
        cmd.setPayType(payType);
        return cmd;
    }

    private SettleBill billOf(Long apptId) {
        return settleBillMapper.selectOne(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getApptId, apptId).last("LIMIT 1"));
    }

    private int remainTimes(Long cardId) {
        return cardMapper.selectById(cardId).getRemainTimes();
    }

    /**
     * ① 卡担保 + total &gt; 担保额 + 操作员选 CASH：
     * 差额必须作为「现金应收」显式返回，余额一分不动，次卡照常核销 1 次。
     */
    @Test
    void cardGuaranteeOverpayCollectedAsCash() {
        Member m = memberWithBalance(new BigDecimal("300"));
        Pet pet = cat(m);
        Card card = newCard(m);
        Long apptId = arrivedCardAppt(m, pet, card, 9);

        SettleService.ConfirmResult r = settleService.confirm(apptId, confirmCmd("200.00", "CASH"), OPERATOR);

        assertEquals(0, new BigDecimal("200.00").compareTo(r.getTotal()));
        assertEquals("CARD", r.getPayType(), "次卡担保的单据主渠道固定记 CARD");
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getBalancePaid()), "选了 CASH 就不该动余额");
        assertEquals(0, new BigDecimal("80.00").compareTo(r.getCashDue()),
                "200 服务价 − 120 卡担保 = 80 必须由店员线下收，不能静默漏收");

        WalletVO wallet = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("300").compareTo(wallet.getBalance()), "余额应原封不动");
        assertEquals(0, BigDecimal.ZERO.compareTo(wallet.getFrozenAmount()), "卡担保不冻结余额");
        assertEquals(9, remainTimes(card.getId()), "10 次卡核销 1 次后应剩 9 次");

        SettleBill bill = billOf(apptId);
        assertEquals("CARD", bill.getPayType());
        assertTrue(bill.getPayDetail().contains("\"cashDue\":80"),
                "结算单 pay_detail 必须留痕现金应收，实际：" + bill.getPayDetail());
    }

    /** ② 卡担保 + total &gt; 担保额 + 操作员选 BALANCE：差额从余额补收，无现金应收。 */
    @Test
    void cardGuaranteeOverpayCollectedFromBalance() {
        Member m = memberWithBalance(new BigDecimal("300"));
        Pet pet = cat(m);
        Card card = newCard(m);
        Long apptId = arrivedCardAppt(m, pet, card, 10);

        SettleService.ConfirmResult r = settleService.confirm(apptId, confirmCmd("200.00", "BALANCE"), OPERATOR);

        assertEquals(0, new BigDecimal("80.00").compareTo(r.getBalancePaid()), "只补收超出担保额的 80");
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getCashDue()), "余额已补齐，无现金应收");
        assertEquals(0, new BigDecimal("220.00").compareTo(balanceService.wallet(m.getId()).getBalance()),
                "300 − 80 = 220，不应扣满 200（120 已由次卡覆盖）");
        assertEquals(9, remainTimes(card.getId()));
    }

    /**
     * ③ 卡担保 + total &lt; 担保额（改价/少做项目）：
     * 不产生任何应收，也照常核销 1 次 —— 次卡冻的是「一次服务」不是「一个金额」。
     */
    @Test
    void cardGuaranteeUnderpayStillConsumesOneUse() {
        Member m = memberWithBalance(new BigDecimal("300"));
        Pet pet = cat(m);
        Card card = newCard(m);
        Long apptId = arrivedCardAppt(m, pet, card, 11);

        SettleService.ConfirmResult r = settleService.confirm(apptId, confirmCmd("100.00", "CASH"), OPERATOR);

        assertEquals(0, BigDecimal.ZERO.compareTo(r.getBalancePaid()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getCashDue()), "低于担保额不该冒出应收");
        assertEquals(0, new BigDecimal("300").compareTo(balanceService.wallet(m.getId()).getBalance()));
        assertEquals(9, remainTimes(card.getId()), "改价不该让顾客损失次数，仍核销 1 次");
        assertEquals(cn.petshop.common.enums.ApptStatus.SETTLED.name(),
                apptMapper.selectById(apptId).getStatus());
    }
}
