package cn.petshop;

import cn.petshop.common.util.JwtUtil;
import cn.petshop.common.web.BizException;
import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.service.MemberService;
import cn.petshop.member.service.PetService;
import cn.petshop.wallet.entity.PayOrder;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.PayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全回归测试（对应审查 C-1 / M-7 / MINOR-5 / MINOR-6 与测试覆盖评估建议的 ①②）。
 *
 * 重点是把「入账必须对账」「订单必须归属本人」「退款不得超额」「宠物删除不得破坏历史」
 * 这四条铁律固化成会自动失败的断言 —— C-1 之所以能潜伏，就是因为当时没有任何负向用例。
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityRegressionTest {

    @Autowired
    private PayService payService;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private PetService petService;

    private Member newMember() {
        return memberService.findOrCreate("mock-sec-" + System.nanoTime(), 1L);
    }

    private BigDecimal balanceOf(Long memberId) {
        return balanceService.wallet(memberId).getBalance();
    }

    private PayOrder newOrder(Member m, String amount) {
        return payService.createRechargeOrder(m.getId(), 1L, new BigDecimal(amount), "WXPAY");
    }

    // ---------- ① mock-notify 金额必须与订单对账 ----------

    @Test
    void payCallbackRejectsAmountMismatch() {
        Member m = newMember();
        PayOrder order = newOrder(m, "100");
        // 客户端自报 1000，与订单 100 不符 → 必须拒绝
        assertThrows(BizException.class, () -> payService.onPaySuccess(
                order.getPayNo(), m.getId(), "MOCK-1", new BigDecimal("1000"), BigDecimal.ZERO));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())), "对账失败不得入账");
    }

    @Test
    void payCallbackRejectsPrincipalPlusGiftMismatch() {
        Member m = newMember();
        PayOrder order = newOrder(m, "100");
        // 本金 60 + 赠送 60 = 120 ≠ 100 → 拒绝
        assertThrows(BizException.class, () -> payService.onPaySuccess(
                order.getPayNo(), m.getId(), "MOCK-1b", new BigDecimal("60"), new BigDecimal("60")));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())));
    }

    @Test
    void payCallbackRejectsNegativeAmounts() {
        Member m = newMember();
        PayOrder order = newOrder(m, "100");
        assertThrows(BizException.class, () -> payService.onPaySuccess(
                order.getPayNo(), m.getId(), "MOCK-1c", new BigDecimal("-100"), new BigDecimal("200")));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())));
    }

    // ---------- ② 只能给自己的支付单触发入账 ----------

    @Test
    void payCallbackRejectsOtherMembersOrder() {
        Member owner = newMember();
        Member attacker = newMember();
        PayOrder order = newOrder(owner, "100");

        assertThrows(BizException.class, () -> payService.onPaySuccess(
                order.getPayNo(), attacker.getId(), "MOCK-2", new BigDecimal("100"), BigDecimal.ZERO));

        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(owner.getId())), "受害会员余额不得变化");
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(attacker.getId())), "攻击者不得白得余额");
    }

    @Test
    void payCallbackIsIdempotentOnReplay() {
        Member m = newMember();
        PayOrder order = newOrder(m, "100");
        assertTrue(payService.onPaySuccess(order.getPayNo(), m.getId(), "MOCK-3",
                new BigDecimal("100"), BigDecimal.ZERO));
        // 回调重放：CAS CREATED→SUCCESS 失败，返回 false 且不再入账
        assertFalse(payService.onPaySuccess(order.getPayNo(), m.getId(), "MOCK-3",
                new BigDecimal("100"), BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("100").compareTo(balanceOf(m.getId())), "重放不得重复入账");
    }

    @Test
    void payCallbackAcceptsExactAmountAndCreditsGift() {
        Member m = newMember();
        PayOrder order = newOrder(m, "120");
        assertTrue(payService.onPaySuccess(order.getPayNo(), m.getId(), "MOCK-4",
                new BigDecimal("100"), new BigDecimal("20")));
        assertEquals(0, new BigDecimal("120").compareTo(balanceOf(m.getId())));
    }

    // ---------- ③ 退款不得把余额扣成负数 ----------

    @Test
    void refundCannotExceedAvailableBalance() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("100"), BigDecimal.ZERO, "PY-SEC-RF1");
        assertThrows(BizException.class, () -> balanceService.refund(
                m.getId(), new BigDecimal("150"), BigDecimal.ZERO, "RF-SEC-1", "tester", "超额退款"));
        assertEquals(0, new BigDecimal("100").compareTo(balanceOf(m.getId())), "超额退款必须整体回滚");
    }

    @Test
    void refundWithinBalanceSucceeds() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("100"), BigDecimal.ZERO, "PY-SEC-RF2");
        balanceService.refund(m.getId(), new BigDecimal("40"), BigDecimal.ZERO, "RF-SEC-2", "tester", "正常退款");
        assertEquals(0, new BigDecimal("60").compareTo(balanceOf(m.getId())));
    }

    // ---------- ④ 宠物软删除不得破坏历史 ----------

    @Test
    void softDeletedPetKeepsHistoryButBlocksNewBusiness() {
        Member m = newMember();
        Pet pet = new Pet();
        pet.setName("雪球");
        pet.setType("CAT");
        pet.setHair("SHORT");
        pet.setWeight(new BigDecimal("4.20"));
        Pet saved = petService.create(m.getId(), pet);

        petService.delete(m.getId(), saved.getId());

        assertTrue(petService.listByMember(m.getId()).stream()
                .noneMatch(p -> p.getId().equals(saved.getId())), "软删除后不应出现在列表");
        assertEquals("雪球", petService.namesIgnoreDeleted(List.of(saved.getId())).get(saved.getId()),
                "历史预约/健康档案仍要能取到宠物名");
        assertThrows(BizException.class, () -> petService.getOwnedPet(m.getId(), saved.getId()),
                "已删除的宠物不能再用于新预约");
    }

    // ---------- ⑤ JWT 解析与签名 ----------

    @Test
    void jwtParserValidatesSignatureAndExpiry() {
        String secret = "unit-test-secret";
        String token = JwtUtil.sign(42L, 60_000L, secret);
        assertEquals(42L, JwtUtil.parseMemberId(token, secret));

        assertNull(JwtUtil.parseMemberId(token, "other-secret"), "换密钥必须解析失败");
        assertNull(JwtUtil.parseMemberId(null, secret));
        assertNull(JwtUtil.parseMemberId("not-a-token", secret));
        assertNull(JwtUtil.parseMemberId(JwtUtil.sign(42L, -1_000L, secret), secret), "过期令牌必须拒绝");

        String[] parts = token.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 1) + "X";
        assertNull(JwtUtil.parseMemberId(parts[0] + "." + tamperedPayload + "." + parts[2], secret),
                "篡改 payload 必须被签名校验拦截");
    }
}
