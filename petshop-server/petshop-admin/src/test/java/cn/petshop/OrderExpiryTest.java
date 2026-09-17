package cn.petshop;

import cn.petshop.mall.entity.Goods;
import cn.petshop.mall.entity.GoodsOrder;
import cn.petshop.mall.entity.InventoryLog;
import cn.petshop.mall.entity.Sku;
import cn.petshop.mall.job.OrderExpiryJob;
import cn.petshop.mall.mapper.GoodsMapper;
import cn.petshop.mall.mapper.GoodsOrderMapper;
import cn.petshop.mall.mapper.InventoryLogMapper;
import cn.petshop.mall.mapper.SkuMapper;
import cn.petshop.mall.service.MallOrderService;
import cn.petshop.mall.service.OrderVO;
import cn.petshop.member.entity.Member;
import cn.petshop.member.service.MemberService;
import cn.petshop.wallet.service.BalanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时未支付订单自动关单的回归测试。
 *
 * <p>要守住的四件事：① 超时的未支付单必须被关且库存回补；② 没超时的不能碰；
 * ③ <b>已支付的绝不能被误关</b>（这是最伤用户的一条 —— 钱付了单被系统关掉）；
 * ④ 关单不会重复回补库存。
 *
 * <p>关单任务在测试环境被配置成"注册但不自动触发"（初始延迟 1 小时），
 * 所有用例都显式调用 {@link OrderExpiryJob#expire}，因此结果与机器速度、调度时机无关。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderExpiryTest {

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private OrderExpiryJob expiryJob;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private GoodsMapper goodsMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private GoodsOrderMapper orderMapper;
    @Autowired
    private InventoryLogMapper inventoryLogMapper;

    private static final int TIMEOUT_MINUTES = 15;
    private static final int BATCH = 100;

    private Member newMember() {
        return memberService.findOrCreate("mock-expiry-" + System.nanoTime(), 1L);
    }

    private Sku newSku(int stock, String price) {
        Goods g = new Goods();
        g.setStoreId(1L);
        g.setName("关单测试商品-" + System.nanoTime());
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

    /** 把下单时间往前挪，制造"已经超时"的订单。真实时间不会因为测试而加速，只能改数据。 */
    private void backdate(String orderNo, int minutesAgo) {
        orderMapper.update(null, new LambdaUpdateWrapper<GoodsOrder>()
                .eq(GoodsOrder::getOrderNo, orderNo)
                .set(GoodsOrder::getCreatedAt, LocalDateTime.now().minusMinutes(minutesAgo)));
    }

    private String statusOf(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<GoodsOrder>()
                .eq(GoodsOrder::getOrderNo, orderNo)).getStatus();
    }

    private int stockOf(Long skuId) {
        return skuMapper.selectById(skuId).getStock();
    }

    private List<InventoryLog> logsOf(String orderNo) {
        return inventoryLogMapper.selectList(new LambdaQueryWrapper<InventoryLog>()
                .eq(InventoryLog::getBizNo, orderNo)
                .orderByAsc(InventoryLog::getId));
    }

    // ---------- ① 超时的未支付单被关且回补库存 ----------

    @Test
    void expiredUnpaidOrderIsClosedAndStockRestored() {
        Member m = newMember();
        Sku sku = newSku(10, "30.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 4));
        assertEquals(6, stockOf(sku.getId()), "下单即锁库存");

        backdate(created.getOrderNo(), 20);
        int closed = expiryJob.expire(TIMEOUT_MINUTES, BATCH);

        assertTrue(closed >= 1, "至少应关掉这一张超时单");
        assertEquals(GoodsOrder.STATUS_CANCELLED, statusOf(created.getOrderNo()), "超时未支付单应被关闭");
        assertEquals(10, stockOf(sku.getId()), "关单必须把库存还回来");

        // 流水要能回答"这批货是谁放回去的"：operator 落 SYSTEM 而不是空
        List<InventoryLog> logs = logsOf(created.getOrderNo());
        InventoryLog restore = logs.stream()
                .filter(l -> InventoryLog.BIZ_RESTORE.equals(l.getBizType()))
                .findFirst().orElse(null);
        assertNotNull(restore, "关单必须写一条回补库存流水");
        assertEquals(4, restore.getQty());
        assertEquals(10, restore.getStockAfter());
        assertEquals("SYSTEM", restore.getOperator());
    }

    // ---------- ② 没超时的不能碰 ----------

    @Test
    void freshUnpaidOrderIsUntouched() {
        Member m = newMember();
        Sku sku = newSku(5, "30.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));

        expiryJob.expire(TIMEOUT_MINUTES, BATCH);

        assertEquals(GoodsOrder.STATUS_CREATED, statusOf(created.getOrderNo()),
                "刚下的单远不到 15 分钟，不能被动到");
        assertEquals(4, stockOf(sku.getId()), "没关单就不该回补库存");
    }

    /** 边界：刚好差一分钟没到阈值，必须留着。 */
    @Test
    void orderJustInsideTheWindowIsKept() {
        Member m = newMember();
        Sku sku = newSku(5, "30.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));

        backdate(created.getOrderNo(), TIMEOUT_MINUTES - 1);
        expiryJob.expire(TIMEOUT_MINUTES, BATCH);

        assertEquals(GoodsOrder.STATUS_CREATED, statusOf(created.getOrderNo()),
                "14 分钟的单还在有效期内，不能关");
        assertEquals(4, stockOf(sku.getId()));
    }

    // ---------- ③ 已支付的绝不能被误关（最关键的一条） ----------

    @Test
    void paidOrderIsNeverClosed() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("100.00"), BigDecimal.ZERO, "RC-" + System.nanoTime());
        Sku sku = newSku(5, "30.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        orderService.pay(m.getId(), created.getOrderNo(), null);

        // 故意把下单时间也挪到很久以前：如果关单只看 created_at、不看 status，就会误关
        backdate(created.getOrderNo(), 999);
        expiryJob.expire(TIMEOUT_MINUTES, BATCH);

        assertEquals(GoodsOrder.STATUS_PAID, statusOf(created.getOrderNo()),
                "已支付的订单无论多久都不能被关单任务关掉");
        assertEquals(4, stockOf(sku.getId()), "已支付单的库存不该被回补");
        assertEquals(0, new BigDecimal("70.00").compareTo(balanceService.wallet(m.getId()).getBalance()),
                "关单任务不得触碰余额");
    }

    // ---------- ④ 不重复回补 ----------

    @Test
    void alreadyCancelledOrderIsNotRestoredTwice() {
        Member m = newMember();
        Sku sku = newSku(5, "30.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 2));
        orderService.cancel(m.getId(), created.getOrderNo());
        assertEquals(5, stockOf(sku.getId()), "顾客自己取消后库存已回补");
        int stockAfterCancel = stockOf(sku.getId());
        int logsAfterCancel = logsOf(created.getOrderNo()).size();

        backdate(created.getOrderNo(), 20);
        expiryJob.expire(TIMEOUT_MINUTES, BATCH);

        // 只断言"我这张单"的状态与流水，不看 expire() 的全局返回值 ——
        // 整个测试类共用一个 H2 库，别的方法留下的超时单同样会被这一轮扫描捞走，
        // 拿全局计数做断言会随用例执行顺序时绿时红。
        assertEquals(GoodsOrder.STATUS_CANCELLED, statusOf(created.getOrderNo()));
        assertEquals(stockAfterCancel, stockOf(sku.getId()), "库存不能被回补第二次");
        assertEquals(logsAfterCancel, logsOf(created.getOrderNo()).size(),
                "已取消的单不该再产生一条回补流水");
    }

    @Test
    void secondSweepDoesNotReprocessClosedOrders() {
        Member m = newMember();
        Sku sku = newSku(5, "30.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        backdate(created.getOrderNo(), 20);

        assertTrue(expiryJob.expire(TIMEOUT_MINUTES, BATCH) >= 1);
        int stockAfterFirst = stockOf(sku.getId());
        int logsAfterFirst = logsOf(created.getOrderNo()).size();

        expiryJob.expire(TIMEOUT_MINUTES, BATCH);

        assertEquals(GoodsOrder.STATUS_CANCELLED, statusOf(created.getOrderNo()));
        assertEquals(stockAfterFirst, stockOf(sku.getId()), "第二轮不得再次回补库存");
        assertEquals(logsAfterFirst, logsOf(created.getOrderNo()).size(), "第二轮不得再写回补流水");
    }

    /**
     * 扫描与关单之间被支付的竞态：直接复现「先拿到待关列表，再付款，然后才执行关单」。
     *
     * <p>这是 {@code casExpire} 里那个 {@code created_at < deadline} 条件存在的意义 ——
     * 关单动作自己复核一遍，而不是盲信扫描结果。
     */
    @Test
    void orderPaidBetweenScanAndCloseIsNotClosed() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("100.00"), BigDecimal.ZERO, "RC-" + System.nanoTime());
        Sku sku = newSku(5, "30.00");
        OrderVO created = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
        backdate(created.getOrderNo(), 20);

        LocalDateTime deadline = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<String> scanned = orderService.findExpiredOrderNos(deadline, BATCH);
        assertTrue(scanned.contains(created.getOrderNo()), "扫描结果里应当有这张超时单");

        // 就在这一瞬间，顾客付了钱
        orderService.pay(m.getId(), created.getOrderNo(), null);

        assertFalse(orderService.closeExpiredOrder(created.getOrderNo(), deadline),
                "付款后关单必须失败，否则用户钱付了单却没了");
        assertEquals(GoodsOrder.STATUS_PAID, statusOf(created.getOrderNo()));
        assertEquals(4, stockOf(sku.getId()));
    }

    // ---------- ⑤ 单轮工作量有界 ----------

    @Test
    void sweepIsBoundedByBatchSize() {
        Member m = newMember();
        Sku sku = newSku(20, "10.00");
        List<String> mine = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OrderVO vo = orderService.create(m.getId(), 1L, oneItem(sku.getId(), 1));
            backdate(vo.getOrderNo(), 20);
            mine.add(vo.getOrderNo());
        }

        int closed = expiryJob.expire(TIMEOUT_MINUTES, 2);

        assertEquals(2, closed, "批量上限为 2 时就只能关 2 笔，不能一轮清库");
        long stillCreated = mine.stream()
                .filter(no -> GoodsOrder.STATUS_CREATED.equals(statusOf(no)))
                .count();
        assertTrue(stillCreated >= 3, "剩下 3 张（或更多）应留给下一轮，实际仍未关闭 " + stillCreated + " 张");
    }
}
