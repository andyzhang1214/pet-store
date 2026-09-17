package cn.petshop;

import cn.petshop.common.enums.LedgerBizType;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.Member;
import cn.petshop.member.service.MemberService;
import cn.petshop.settle.service.RefundService;
import cn.petshop.wallet.entity.BalanceLedger;
import cn.petshop.wallet.entity.Card;
import cn.petshop.wallet.entity.CardType;
import cn.petshop.wallet.entity.RefundApply;
import cn.petshop.wallet.mapper.BalanceLedgerMapper;
import cn.petshop.wallet.mapper.CardMapper;
import cn.petshop.wallet.mapper.CardTypeMapper;
import cn.petshop.wallet.mapper.RefundApplyMapper;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.CardService;
import cn.petshop.wallet.service.MemberCardVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退款只入余额的回归测试。
 *
 * 三条铁律：① 同一笔退款只能入账一次；② 退款金额由服务端定，客户端说了不算；
 * ③ 退完之后余额恒等于账本累加。第 ① 条是这套逻辑唯一的资金风险点，所以既有串行的
 * 重复审核用例，也有真并发的双线程用例。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundFlowTest {

    @Autowired
    private RefundService refundService;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private CardService cardService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private CardTypeMapper cardTypeMapper;
    @Autowired
    private CardMapper cardMapper;
    @Autowired
    private RefundApplyMapper refundApplyMapper;
    @Autowired
    private BalanceLedgerMapper balanceLedgerMapper;

    private static final BigDecimal CARD_PRICE = new BigDecimal("1080.00");
    private static final String OPERATOR = "店长张三";

    private Member newMember() {
        return memberService.findOrCreate("mock-refund-" + System.nanoTime(), 1L);
    }

    private Card newCard(Member m) {
        CardType type = new CardType();
        type.setStoreId(1L);
        type.setName("猫咪洗澡10次卡");
        type.setServiceIds("[]");
        type.setSpecIds("[]");
        type.setTimes(10);
        type.setPrice(CARD_PRICE);
        type.setValidMode("MONTHS");
        type.setValidMonths(12);
        type.setStatus("ENABLED");
        cardTypeMapper.insert(type);
        return cardService.purchase(m.getId(), type.getId(), "tester");
    }

    private BigDecimal balanceOf(Long memberId) {
        return balanceService.wallet(memberId).getBalance();
    }

    private long refundLedgerCount(Long memberId) {
        return balanceLedgerMapper.selectList(new LambdaQueryWrapper<BalanceLedger>()
                        .eq(BalanceLedger::getMemberId, memberId)).stream()
                .filter(l -> l.getBizType() == LedgerBizType.REFUND_IN)
                .count();
    }

    private RefundService.ApplyCmd applyCmd(String cardNo) {
        RefundService.ApplyCmd cmd = new RefundService.ApplyCmd();
        cmd.setBizType(RefundApply.BIZ_CARD);
        cmd.setBizNo(cardNo);
        cmd.setReason("用不上，申请退款");
        return cmd;
    }

    private RefundService.AuditCmd approve() {
        RefundService.AuditCmd cmd = new RefundService.AuditCmd();
        cmd.setApproved(true);
        return cmd;
    }

    // ---------- ① 正常链路：退到余额 ----------

    @Test
    void cardRefundCreditsBalance() {
        Member m = newMember();
        Card card = newCard(m);

        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));
        assertEquals(RefundApply.STATUS_APPLYING, apply.getStatus());
        assertEquals(0, CARD_PRICE.compareTo(apply.getAmount()), "金额必须由服务端按购买实付计算");

        RefundApply done = refundService.audit(apply.getId(), approve(), OPERATOR);
        assertEquals(RefundApply.STATUS_REFUNDED, done.getStatus());
        assertEquals(0, CARD_PRICE.compareTo(balanceOf(m.getId())), "退款全额入可用余额");
        assertEquals(1, refundLedgerCount(m.getId()), "只产生一条退款入账流水");

        Card after = cardMapper.selectById(card.getId());
        assertEquals(Card.STATUS_REFUNDED, after.getStatus(), "卡必须置为已退款");
        assertEquals(0, after.getRemainTimes());
        assertEquals(RefundApply.STATUS_REFUNDED, refundApplyMapper.selectById(apply.getId()).getStatus());
    }

    // ---------- ② 重复入账的两道防线 ----------

    @Test
    void secondAuditCreditsNothing() {
        Member m = newMember();
        Card card = newCard(m);
        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));
        refundService.audit(apply.getId(), approve(), OPERATOR);

        assertThrows(BizException.class, () -> refundService.audit(apply.getId(), approve(), OPERATOR),
                "已处理的退款单不得再次审核");
        assertEquals(0, CARD_PRICE.compareTo(balanceOf(m.getId())), "重复审核不得二次入账");
        assertEquals(1, refundLedgerCount(m.getId()));
    }

    @Test
    void concurrentAuditCreditsOnlyOnce() throws Exception {
        Member m = newMember();
        Card card = newCard(m);
        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    refundService.audit(apply.getId(), approve(), OPERATOR);
                    success.incrementAndGet();
                } catch (BizException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发审核应在 30s 内结束");

        assertEquals(1, success.get(), "只允许一个事务审核成功");
        assertEquals(1, rejected.get(), "另一个必须被状态 CAS 拒绝");
        assertEquals(0, CARD_PRICE.compareTo(balanceOf(m.getId())), "并发双击不得重复入账");
        assertEquals(1, refundLedgerCount(m.getId()), "只允许一条退款入账流水");
    }

    @Test
    void ledgerBizNoIsIdempotencyBackstop() {
        Member m = newMember();
        balanceService.refundToBalance(m.getId(), CARD_PRICE, BigDecimal.ZERO, "RF-FIXED-1", OPERATOR, "手工");
        assertThrows(BizException.class, () -> balanceService.refundToBalance(
                m.getId(), CARD_PRICE, BigDecimal.ZERO, "RF-FIXED-1", OPERATOR, "手工重放"));
        assertEquals(0, CARD_PRICE.compareTo(balanceOf(m.getId())), "同号退款流水不得重复入账");
    }

    // ---------- ③ 金额与归属不受客户端摆布 ----------

    @Test
    void refundAmountComesFromPaidAmountNotCurrentPrice() {
        Member m = newMember();
        Card card = newCard(m);
        // 卡买完就改价：退款必须按购买实付 1080，而不是改后的 1 元
        CardType type = cardTypeMapper.selectById(card.getCardTypeId());
        type.setPrice(new BigDecimal("1.00"));
        cardTypeMapper.updateById(type);

        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));
        assertEquals(0, CARD_PRICE.compareTo(apply.getAmount()), "改价不得影响已购卡的退款金额");
        refundService.audit(apply.getId(), approve(), OPERATOR);
        assertEquals(0, CARD_PRICE.compareTo(balanceOf(m.getId())));
    }

    @Test
    void cannotRefundOthersCard() {
        Member owner = newMember();
        Member attacker = newMember();
        Card card = newCard(owner);

        assertThrows(BizException.class,
                () -> refundService.apply(attacker.getId(), 1L, applyCmd(card.getCardNo())),
                "不得对他人的卡发起退款");
        assertThrows(BizException.class, () -> refundService.apply(
                attacker.getId(), 1L, applyCmd("CD00000000-not-exist")));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(attacker.getId())));
    }

    @Test
    void usedCardCannotBeRefunded() {
        Member m = newMember();
        Card card = newCard(m);
        Card used = new Card();
        used.setId(card.getId());
        used.setRemainTimes(card.getTotalTimes() - 1);
        cardMapper.updateById(used);

        assertThrows(BizException.class,
                () -> refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo())),
                "用过的卡不支持线上退款");
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())));
    }

    @Test
    void cardWithPreDeductCannotBeRefunded() {
        Member m = newMember();
        Card card = newCard(m);
        Card pre = new Card();
        pre.setId(card.getId());
        pre.setPreDeductCount(1);
        cardMapper.updateById(pre);

        assertThrows(BizException.class,
                () -> refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo())),
                "有预扣（占用中）的卡不得退款");
    }

    @Test
    void expiredUnusedCardCanStillBeRefunded() {
        Member m = newMember();
        Card card = newCard(m);
        Card expired = new Card();
        expired.setId(card.getId());
        expired.setValidUntil(LocalDate.now().minusDays(1));
        expired.setStatus(Card.STATUS_EXPIRED);
        cardMapper.updateById(expired);

        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));
        refundService.audit(apply.getId(), approve(), OPERATOR);
        assertEquals(0, CARD_PRICE.compareTo(balanceOf(m.getId())),
                "过期但一次没用的卡同样可退，是否退由门店在审核环节裁决（口径见 CardService.isRefundable）");
    }

    @Test
    void unsupportedSourceIsRejectedExplicitly() {
        Member m = newMember();

        // 预约取消走担保解冻、结算多收的链路尚未补齐 —— 这两类仍显式拒绝，而不是假装支持
        RefundService.ApplyCmd appt = new RefundService.ApplyCmd();
        appt.setBizType(RefundApply.BIZ_APPOINT);
        appt.setBizNo("AP202601010000000001");
        assertThrows(BizException.class, () -> refundService.apply(m.getId(), 1L, appt));

        RefundService.ApplyCmd settle = new RefundService.ApplyCmd();
        settle.setBizType(RefundApply.BIZ_SETTLE);
        settle.setBizNo("ST202601010000000001");
        assertThrows(BizException.class, () -> refundService.apply(m.getId(), 1L, settle));

        RefundService.ApplyCmd junk = new RefundService.ApplyCmd();
        junk.setBizType("WHATEVER");
        junk.setBizNo("X1");
        assertThrows(BizException.class, () -> refundService.apply(m.getId(), 1L, junk));

        // 商品订单已开放线上退款（见 MallOrderFlowTest）：不存在的订单同样要拒绝，
        // 但错因是"订单不存在"而不是"来源未开放" —— 断言错误码把这个区别钉住
        RefundService.ApplyCmd goods = new RefundService.ApplyCmd();
        goods.setBizType(RefundApply.BIZ_GOODS);
        goods.setBizNo("GO202601010000000001");
        BizException ex = assertThrows(BizException.class, () -> refundService.apply(m.getId(), 1L, goods));
        assertEquals(ErrorCode.BIZ_NOT_FOUND, ex.getErrorCode());
    }

    // ---------- ④ 驳回 / 撤回 / 重复申请 ----------

    @Test
    void rejectedRefundCreditsNothingAndCanBeReapplied() {
        Member m = newMember();
        Card card = newCard(m);
        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));

        RefundService.AuditCmd reject = new RefundService.AuditCmd();
        reject.setApproved(false);
        reject.setRemark("顾客已到店协商换卡");
        RefundApply rejected = refundService.audit(apply.getId(), reject, OPERATOR);
        assertEquals(RefundApply.STATUS_REJECTED, rejected.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())), "驳回不得动余额");

        // 驳回后允许重新申请，不会被"重复申请"校验挡住
        RefundApply again = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));
        assertEquals(RefundApply.STATUS_APPLYING, again.getStatus());
    }

    @Test
    void rejectRequiresReason() {
        Member m = newMember();
        Card card = newCard(m);
        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));

        RefundService.AuditCmd reject = new RefundService.AuditCmd();
        reject.setApproved(false);
        assertThrows(BizException.class, () -> refundService.audit(apply.getId(), reject, OPERATOR),
                "驳回必须填理由");
    }

    @Test
    void cancelledRefundCannotBeAudited() {
        Member m = newMember();
        Card card = newCard(m);
        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));

        RefundApply cancelled = refundService.cancel(m.getId(), apply.getId());
        assertEquals(RefundApply.STATUS_CANCELLED, cancelled.getStatus());
        assertThrows(BizException.class, () -> refundService.audit(apply.getId(), approve(), OPERATOR));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())));
    }

    @Test
    void cannotCancelOthersRefund() {
        Member owner = newMember();
        Member attacker = newMember();
        Card card = newCard(owner);
        RefundApply apply = refundService.apply(owner.getId(), 1L, applyCmd(card.getCardNo()));

        assertThrows(BizException.class, () -> refundService.cancel(attacker.getId(), apply.getId()),
                "不得撤回他人的退款单");
    }

    @Test
    void duplicateApplyOnSameCardIsRejected() {
        Member m = newMember();
        Card card = newCard(m);
        refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));
        assertThrows(BizException.class,
                () -> refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo())),
                "同一张卡不得重复申请退款");
    }

    // ---------- ⑤ 人工退款 ----------

    @Test
    void manualRefundCreditsBalanceAndKeepsAuditTrail() {
        Member m = newMember();
        RefundService.ManualCmd cmd = new RefundService.ManualCmd();
        cmd.setMemberId(m.getId());
        cmd.setAmount(new BigDecimal("50.00"));
        cmd.setReason("洗澡等待过久，补偿");

        RefundApply apply = refundService.manualRefund(1L, cmd, OPERATOR);
        assertEquals(RefundApply.STATUS_REFUNDED, apply.getStatus());
        assertEquals(RefundApply.BIZ_MANUAL, apply.getBizType());
        assertEquals(OPERATOR, apply.getAuditor(), "人工退款必须留下操作人");
        assertEquals(0, new BigDecimal("50.00").compareTo(balanceOf(m.getId())));
    }

    @Test
    void manualRefundRequiresReasonAndOperator() {
        Member m = newMember();
        RefundService.ManualCmd noReason = new RefundService.ManualCmd();
        noReason.setMemberId(m.getId());
        noReason.setAmount(new BigDecimal("50.00"));
        assertThrows(BizException.class, () -> refundService.manualRefund(1L, noReason, OPERATOR));

        // 操作人为空必须拒绝。这条防线在改成"服务端注入"之后依然要留着：
        // 一旦将来有人在某条新调用路径上漏传，落库的 auditor 就是 null，
        // 那比填了个假名字更糟 —— 它看起来像"系统自动"，实际是有人动了手却没人知道是谁。
        RefundService.ManualCmd blankOperator = new RefundService.ManualCmd();
        blankOperator.setMemberId(m.getId());
        blankOperator.setAmount(new BigDecimal("50.00"));
        blankOperator.setReason("补偿");
        assertThrows(BizException.class, () -> refundService.manualRefund(1L, blankOperator, null));
        assertThrows(BizException.class, () -> refundService.manualRefund(1L, blankOperator, "   "));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())));
    }

    // ---------- ⑥ 余额恒等于账本累加 ----------

    @Test
    void balanceAlwaysEqualsLedgerSumAfterRefund() {
        Member m = newMember();
        Card card = newCard(m);
        // 先充点钱，制造混合流水
        balanceService.recharge(m.getId(), new BigDecimal("200.00"), new BigDecimal("20.00"), "PY-RF-MIX");
        RefundApply apply = refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));
        refundService.audit(apply.getId(), approve(), OPERATOR);

        List<BalanceLedger> all = balanceLedgerMapper.selectList(new LambdaQueryWrapper<BalanceLedger>()
                .eq(BalanceLedger::getMemberId, m.getId()));
        BigDecimal sum = all.stream()
                .map(l -> nz(l.getPrincipalAmt()).add(nz(l.getGiftAmt())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(balanceOf(m.getId())),
                "任意时刻余额必须等于流水累加（220 充值 + 1080 退款）");
        assertEquals(0, new BigDecimal("1300.00").compareTo(balanceOf(m.getId())));
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------- ⑦ 卡状态与退款单列表 ----------

    @Test
    void myRefundsAndMyCardsAreScopedToMember() {
        Member m = newMember();
        Card card = newCard(m);
        refundService.apply(m.getId(), 1L, applyCmd(card.getCardNo()));

        assertEquals(1, refundService.listMine(m.getId()).size());
        assertEquals(1, cardService.listByMember(m.getId()).size());

        Member other = newMember();
        assertTrue(refundService.listMine(other.getId()).isEmpty(), "退款单列表必须按会员隔离");
        assertEquals(LocalDate.now().plusMonths(12), cardService.listByMember(m.getId()).get(0).getValidUntil());
        assertEquals(0, CARD_PRICE.compareTo(cardService.listByMember(m.getId()).get(0).getPaidAmount()));

        // 钱包页展示用的 VO：卡类型名与可退标识都必须由服务端给出
        List<MemberCardVO> vo = cardService.listForMember(m.getId());
        assertEquals(1, vo.size());
        assertEquals("猫咪洗澡10次卡", vo.get(0).getName());
        assertTrue(vo.get(0).isRefundable(), "未使用的卡在前端应显示可退");
        assertTrue(cardService.listForMember(other.getId()).isEmpty(), "次卡列表必须按会员隔离");
    }

    // ---------- ⑧ 人工退款幂等（对应审查 P2-1 与「建议补的用例 ③」） ----------

    /**
     * 人工退款没有外部业务单号可做幂等依据，靠调用方给一个 {@code idempotencyKey}。
     * 顺序重试（网络超时后用户再点一次）与并发双击都必须只入账一次。
     *
     * <p>这条路径的杀伤力在于它是<b>唯一</b>由后台直接发起、即时生效的资金动作：
     * 没有「申请 → 审核」两道 CAS 拦着，漏了幂等就是点一次退一次，
     * 靠人眼盯着退款列表去重不现实。
     */
    @Test
    void manualRefundRetryWithSameIdemKeyCreditsOnce() {
        Member m = newMember();
        String idemKey = "idem-" + System.nanoTime();

        RefundApply first = refundService.manualRefund(1L, manualCmd(m.getId(), "50.00", idemKey), OPERATOR);
        RefundApply replay = refundService.manualRefund(1L, manualCmd(m.getId(), "50.00", idemKey), OPERATOR);

        assertEquals(first.getId(), replay.getId(), "重放必须把首次的退款单原样还回去");
        assertEquals(first.getRefundNo(), replay.getRefundNo(), "重放不得生成新的退款单号");
        assertEquals(1, manualRefundCount(m.getId()), "同一幂等键只能落一条人工退款单");
        assertEquals(1, refundLedgerCount(m.getId()), "同一幂等键只能入账一次");
        assertEquals(0, new BigDecimal("50.00").compareTo(balanceOf(m.getId())), "余额只增加一次 50");
    }

    /** 并发双击：唯一键必须在动钱之前生效，后到的事务回放首次结果而不是再入一次账。 */
    @Test
    void concurrentManualRefundWithSameIdemKeyCreditsOnce() throws Exception {
        Member m = newMember();
        String idemKey = "idem-c-" + System.nanoTime();

        AtomicInteger success = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        refundService.manualRefund(1L, manualCmd(m.getId(), "50.00", idemKey), OPERATOR);
                        success.incrementAndGet();
                    } catch (Exception ignored) {
                        // 唯一键冲突走的是回放分支，正常不该抛；真抛了下面的断言会兜住
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发人工退款未在 30s 内结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, success.get(), "并发双击都应得到「退款单」这个结果（一次新建、一次回放）");
        assertEquals(1, manualRefundCount(m.getId()), "同一幂等键并发下也只能落一条退款单");
        assertEquals(1, refundLedgerCount(m.getId()), "并发双击只能入账一次");
        assertEquals(0, new BigDecimal("50.00").compareTo(balanceOf(m.getId())), "余额只增加一次 50");
    }

    /** 不带幂等键时保持旧行为：每次调用都是一笔新的退款（抽查类补偿不受影响）。 */
    @Test
    void manualRefundWithoutIdemKeyStaysNonIdempotent() {
        Member m = newMember();
        assertNotEquals(
                refundService.manualRefund(1L, manualCmd(m.getId(), "30.00", null), OPERATOR).getId(),
                refundService.manualRefund(1L, manualCmd(m.getId(), "30.00", null), OPERATOR).getId(),
                "没给幂等键就不该被合并");
        assertEquals(2, refundLedgerCount(m.getId()));
        assertEquals(0, new BigDecimal("60.00").compareTo(balanceOf(m.getId())));
    }

    private RefundService.ManualCmd manualCmd(Long memberId, String amount, String idemKey) {
        RefundService.ManualCmd cmd = new RefundService.ManualCmd();
        cmd.setMemberId(memberId);
        cmd.setAmount(new BigDecimal(amount));
        cmd.setReason("客诉补偿");
        cmd.setIdempotencyKey(idemKey);
        return cmd;
    }

    private long manualRefundCount(Long memberId) {
        return refundApplyMapper.selectCount(new LambdaQueryWrapper<RefundApply>()
                .eq(RefundApply::getMemberId, memberId)
                .eq(RefundApply::getBizType, RefundApply.BIZ_MANUAL));
    }
}
