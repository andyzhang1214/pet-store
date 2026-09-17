package cn.petshop;

import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.mall.entity.Goods;
import cn.petshop.mall.entity.GoodsOrder;
import cn.petshop.mall.entity.Sku;
import cn.petshop.mall.mapper.GoodsMapper;
import cn.petshop.mall.mapper.SkuMapper;
import cn.petshop.mall.service.MallOrderService;
import cn.petshop.mall.service.OrderVO;
import cn.petshop.member.entity.Member;
import cn.petshop.member.service.MemberService;
import cn.petshop.settle.service.RefundService;
import cn.petshop.wallet.entity.RefundApply;
import cn.petshop.wallet.service.BalanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商品订单链路与「商品订单退款」的回归测试。
 *
 * 三条铁律与次卡退款一致：① 钱只能退一次；② 金额由服务端定；③ 退完余额等于账本累加。
 * 额外三条是商品特有的：④ 库存不超卖（靠 CAS 而不是先查后改）；⑤ 退款必须回补库存；
 * ⑥ 已核销的订单不给退。
 */
@SpringBootTest
@ActiveProfiles("test")
class MallOrderFlowTest {

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private GoodsMapper goodsMapper;
    @Autowired
    private SkuMapper skuMapper;

    private static final String OPERATOR = "店长张三";

    private Member newMember() {
        return memberService.findOrCreate("mock-mall-" + System.nanoTime(), 1L);
    }

    private Sku newSku(int stock, String price) {
        Goods g = new Goods();
        g.setStoreId(1L);
        g.setName("测试商品-" + System.nanoTime());
        g.setCategory("洗护");
        g.setStatus(Goods.STATUS_ON_SALE);
        goodsMapper.insert(g);

        Sku s = new Sku();
        s.setGoodsId(g.getId());
        s.setName("标准装");
        s.setPrice(new BigDecimal(price));
        s.setStock(stock);
        skuMapper.insert(s);
        return s;
    }

    private MallOrderService.CreateCmd oneItem(Long skuId, int qty) {
        MallOrderService.CreateCmd.Item it = new MallOrderService.CreateCmd.Item();
        it.setSkuId(skuId);
        it.setQty(qty);
        MallOrderService.CreateCmd cmd = new MallOrderService.CreateCmd();
        cmd.setItems(List.of(it));
        return cmd;
    }

    /** 直接充值造余额。赠礼与本金一起给，用来验证退款按原支付构成拆分。 */
    private void recharge(Member m, String principal, String gift) {
        balanceService.recharge(m.getId(), new BigDecimal(principal), new BigDecimal(gift),
                "RC-" + System.nanoTime());
    }

    private BigDecimal balanceOf(Long memberId) {
        return balanceService.wallet(memberId).getBalance();
    }

    private int stockOf(Long skuId) {
        return skuMapper.selectById(skuId).getStock();
    }

    private RefundService.AuditCmd approve() {
        RefundService.AuditCmd cmd = new RefundService.AuditCmd();
        cmd.setApproved(true);
        return cmd;
    }

    private RefundService.ApplyCmd goodsApply(String orderNo) {
        RefundService.ApplyCmd cmd = new RefundService.ApplyCmd();
        cmd.setBizType(RefundApply.BIZ_GOODS);
        cmd.setBizNo(orderNo);
        cmd.setReason("买错了，申请退款");
        return cmd;
    }

    // ---------- ① 下单与库存 ----------

    @Test
    void createOrderLocksStockAndGeneratesVerifyCode() {
        Member m = newMember();
        Sku sku = newSku(10, "89.00");

        OrderVO vo = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 3));

        assertEquals(GoodsOrder.STATUS_CREATED, vo.getStatus());
        assertEquals(0, new BigDecimal("267.00").compareTo(vo.getTotal()), "金额按 SKU 现价在服务端算");
        assertEquals(7, stockOf(sku.getId()), "下单即锁库存，避免付款时才发现没货");
        assertNotNull(vo.getVerifyCode(), "发单即生成自提核销码");
        assertEquals(8, vo.getVerifyCode().length());
        assertEquals(1, vo.getItems().size());
    }

    @Test
    void insufficientStockIsRejectedAndStockUnchanged() {
        Member m = newMember();
        Sku sku = newSku(2, "50.00");

        assertThrows(BizException.class, () -> orderService.create(m.getId(), 1L, oneItem(sku.getId(), 3)));
        assertEquals(2, stockOf(sku.getId()), "库存不足时不得扣减");
    }

    /** 同一 SKU 拆成多行提交时，数量必须先合并再裁决库存，否则每行单独看都"够"。 */
    @Test
    void splitLinesOfSameSkuAreMergedBeforeStockCheck() {
        Member m = newMember();
        Sku sku = newSku(5, "10.00");

        MallOrderService.CreateCmd.Item a = new MallOrderService.CreateCmd.Item();
        a.setSkuId(sku.getId());
        a.setQty(4);
        MallOrderService.CreateCmd.Item b = new MallOrderService.CreateCmd.Item();
        b.setSkuId(sku.getId());
        b.setQty(4);
        MallOrderService.CreateCmd cmd = new MallOrderService.CreateCmd();
        cmd.setItems(List.of(a, b));

        assertThrows(BizException.class, () -> orderService.create(m.getId(), 1L, cmd),
                "合并后需要 8 件，库存只有 5 件，应当拒绝");
        assertEquals(5, stockOf(sku.getId()));
    }

    /** 库存 CAS 必须在并发下成立 —— 这是"先查后改会超卖"的反证。 */
    @Test
    void concurrentOrdersDoNotOversell() throws Exception {
        Member m = newMember();
        Sku sku = newSku(5, "10.00");

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                    // 库存不足属预期失败
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发下单未在超时内完成");
        pool.shutdown();

        assertEquals(5, ok.get(), "库存 5 件，最多只能成功 5 单");
        assertEquals(0, stockOf(sku.getId()), "库存必须精确扣到 0，不能为负");
    }

    // ---------- ② 支付 ----------

    @Test
    void payByBalanceRecordsSplitMatchingLedger() {
        Member m = newMember();
        // 充 100 送 100 → 本金与赠送金各占一半
        recharge(m, "100.00", "100.00");
        Sku sku = newSku(5, "50.00");

        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        OrderVO paid = orderService.pay(m.getId(), created.getOrderNo(), null);

        assertEquals(GoodsOrder.STATUS_PAID, paid.getStatus());
        assertEquals(GoodsOrder.PAY_BALANCE, paid.getPayType());
        // 花 50 按 50% 拆：本金 25 / 赠送金 25。这两个值直接来自账本口径，不是业务侧重算的
        assertEquals(0, new BigDecimal("25.00").compareTo(paid.getPrincipalPaid()));
        assertEquals(0, new BigDecimal("25.00").compareTo(paid.getGiftPaid()));
        assertEquals(0, new BigDecimal("50.00").compareTo(
                paid.getPrincipalPaid().add(paid.getGiftPaid())), "本金与赠送金之和必须等于订单总额");
        assertEquals(0, new BigDecimal("150.00").compareTo(balanceOf(m.getId())));
        assertTrue(paid.isRefundable(), "已支付未核销的订单应当可退");
    }

    @Test
    void insufficientBalanceCannotPay() {
        Member m = newMember();
        Sku sku = newSku(5, "999.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));

        assertThrows(BizException.class, () -> orderService.pay(m.getId(), created.getOrderNo(), null));

        assertEquals(GoodsOrder.STATUS_CREATED,
                orderService.detail(m.getId(), created.getOrderNo()).getStatus(), "支付失败状态不变");
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(m.getId())), "失败不得扣款");
    }

    /** 微信支付尚未接入商户号与回调验签 —— 显式拒绝，而不是假装支持后静默失败。 */
    @Test
    void wechatPayIsRejectedExplicitly() {
        Member m = newMember();
        Sku sku = newSku(5, "10.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));

        MallOrderService.PayCmd cmd = new MallOrderService.PayCmd();
        cmd.setPayType(GoodsOrder.PAY_WXPAY);
        BizException ex = assertThrows(BizException.class,
                () -> orderService.pay(m.getId(), created.getOrderNo(), cmd));
        assertEquals(ErrorCode.BIZ_PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    void cancelRestoresStock() {
        Member m = newMember();
        Sku sku = newSku(5, "10.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 2));
        assertEquals(3, stockOf(sku.getId()));

        OrderVO cancelled = orderService.cancel(m.getId(), created.getOrderNo());

        assertEquals(GoodsOrder.STATUS_CANCELLED, cancelled.getStatus());
        assertEquals(5, stockOf(sku.getId()), "取消后库存必须回补");
    }

    // ---------- ③ 核销 ----------

    @Test
    void verifyByCodeMarksVerifiedAndIsOneShot() {
        Member m = newMember();
        recharge(m, "100.00", "0");
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(m.getId(), created.getOrderNo(), null);

        String code = created.getVerifyCode();
        OrderVO verified = orderService.verifyByCode(code);

        assertEquals(GoodsOrder.STATUS_VERIFIED, verified.getStatus());
        assertNull(verified.getVerifyCode(), "终态不再展示核销码");
        assertThrows(BizException.class, () -> orderService.verifyByCode(code), "同一码不能核销两次");
    }

    @Test
    void verifyRejectsMalformedCode() {
        assertThrows(BizException.class, () -> orderService.verifyByCode("123"));
        assertThrows(BizException.class, () -> orderService.verifyByCode("00000000"));
        assertThrows(BizException.class, () -> orderService.verifyByCode(null));
    }

    @Test
    void unpaidOrderCannotBeVerified() {
        Member m = newMember();
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));

        assertThrows(BizException.class, () -> orderService.verifyByCode(created.getVerifyCode()),
                "未支付的订单不能被核销");
    }

    // ---------- ④ 商品订单退款 ----------

    @Test
    void goodsRefundReturnsToBalanceAndRestoresStock() {
        Member m = newMember();
        recharge(m, "100.00", "100.00");
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(m.getId(), created.getOrderNo(), null);
        assertEquals(0, new BigDecimal("150.00").compareTo(balanceOf(m.getId())));
        assertEquals(4, stockOf(sku.getId()));

        // 申请：只给来源单号，金额服务端按订单支付构成算
        RefundApply apply = refundService.apply(m.getId(), 1L, goodsApply(created.getOrderNo()));
        assertEquals(RefundApply.STATUS_APPLYING, apply.getStatus());
        assertEquals(0, new BigDecimal("50.00").compareTo(apply.getAmount()));

        // 审核通过
        RefundApply done = refundService.audit(apply.getId(), approve(), OPERATOR);

        assertEquals(RefundApply.STATUS_REFUNDED, done.getStatus());
        // 按原支付构成退回：下单时从本金扣 25、赠送金扣 25，退款就各退 25
        assertEquals(0, new BigDecimal("25.00").compareTo(done.getPrincipalAmt()));
        assertEquals(0, new BigDecimal("25.00").compareTo(done.getGiftAmt()));
        // 余额回到 200
        assertEquals(0, new BigDecimal("200.00").compareTo(balanceOf(m.getId())));
        // 库存回补
        assertEquals(5, stockOf(sku.getId()), "退款必须把库存还回来");
        // 订单终态
        assertEquals(GoodsOrder.STATUS_REFUNDED,
                orderService.detail(m.getId(), created.getOrderNo()).getStatus());
    }

    @Test
    void secondAuditOfGoodsRefundCreditsNothing() {
        Member m = newMember();
        recharge(m, "100.00", "0");
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(m.getId(), created.getOrderNo(), null);

        RefundApply apply = refundService.apply(m.getId(), 1L, goodsApply(created.getOrderNo()));
        refundService.audit(apply.getId(), approve(), OPERATOR);
        BigDecimal afterFirst = balanceOf(m.getId());
        int stockAfterFirst = stockOf(sku.getId());

        assertThrows(BizException.class, () -> refundService.audit(apply.getId(), approve(), OPERATOR),
                "重复审核必须被状态 CAS 拒绝");

        assertEquals(0, afterFirst.compareTo(balanceOf(m.getId())), "重复审核不得再入账");
        assertEquals(stockAfterFirst, stockOf(sku.getId()), "重复审核不得重复回补库存");
    }

    @Test
    void verifiedOrderCannotBeRefunded() {
        Member m = newMember();
        recharge(m, "100.00", "0");
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(m.getId(), created.getOrderNo(), null);
        orderService.verifyByCode(created.getVerifyCode());

        assertThrows(BizException.class, () -> refundService.apply(m.getId(), 1L, goodsApply(created.getOrderNo())),
                "已核销订单不支持线上退款");
    }

    @Test
    void unpaidOrderCannotBeRefunded() {
        Member m = newMember();
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));

        assertThrows(BizException.class, () -> refundService.apply(m.getId(), 1L, goodsApply(created.getOrderNo())),
                "未支付订单没有可退金额");
    }

    @Test
    void cannotRefundOthersOrder() {
        Member a = newMember();
        Member b = newMember();
        recharge(a, "100.00", "0");
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(a.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(a.getId(), created.getOrderNo(), null);

        BizException ex = assertThrows(BizException.class,
                () -> refundService.apply(b.getId(), 1L, goodsApply(created.getOrderNo())));
        assertEquals(ErrorCode.BIZ_NOT_FOUND, ex.getErrorCode(),
                "越权与不存在返回同一错误码，避免被用来探测他人订单号");
    }

    @Test
    void duplicateApplyOnSameOrderIsRejected() {
        Member m = newMember();
        recharge(m, "100.00", "0");
        Sku sku = newSku(5, "50.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(m.getId(), created.getOrderNo(), null);

        refundService.apply(m.getId(), 1L, goodsApply(created.getOrderNo()));
        assertThrows(BizException.class, () -> refundService.apply(m.getId(), 1L, goodsApply(created.getOrderNo())),
                "同一订单不允许并存两张未完结核退款单");
    }

    /** 对账恒等式：余额必须恒等于账本累加。退款后仍要成立。 */
    @Test
    void balanceEqualsLedgerSumAfterGoodsRefund() {
        Member m = newMember();
        recharge(m, "100.00", "50.00");
        Sku sku = newSku(5, "60.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(m.getId(), created.getOrderNo(), null);
        RefundApply apply = refundService.apply(m.getId(), 1L, goodsApply(created.getOrderNo()));
        refundService.audit(apply.getId(), approve(), OPERATOR);

        BigDecimal sum = balanceService.wallet(m.getId()).getBalance();
        assertEquals(0, balanceOf(m.getId()).compareTo(sum));
        // 退款后余额应回到充值总额 150（消费 60 又退回 60）
        assertEquals(0, new BigDecimal("150.00").compareTo(balanceOf(m.getId())));
    }
}
