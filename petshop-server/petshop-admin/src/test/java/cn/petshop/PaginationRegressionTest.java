package cn.petshop;

import cn.petshop.mall.entity.Goods;
import cn.petshop.mall.entity.Sku;
import cn.petshop.mall.mapper.GoodsMapper;
import cn.petshop.mall.mapper.SkuMapper;
import cn.petshop.mall.service.MallOrderService;
import cn.petshop.mall.service.OrderVO;
import cn.petshop.member.entity.Member;
import cn.petshop.member.service.MemberService;
import cn.petshop.settle.service.RefundService;
import cn.petshop.wallet.entity.BalanceLedger;
import cn.petshop.wallet.entity.RefundApply;
import cn.petshop.wallet.mapper.BalanceLedgerMapper;
import cn.petshop.wallet.service.BalanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分页回归测试 —— 锁住「分页必须真的发生」这件事。
 *
 * <h3>这个测试为什么必须存在</h3>
 * 缺 {@code PaginationInnerInterceptor} 时 MyBatis-Plus 不报错、不抛异常：查询照跑，
 * 只是 count 不执行（{@code total} 恒 0）、{@code LIMIT} 不拼（{@code size} 被忽略）。
 * 于是 service 里那些「size 上限 100」的防御全部空转，接口在数据量小时看起来完全正常。
 * 只有断言 {@code records.size() == size} 且 {@code total > 0} 才能发现它 ——
 * 断言「返回了数据」是发现不了的。
 */
@SpringBootTest
@ActiveProfiles("test")
class PaginationRegressionTest {

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
    @Autowired
    private BalanceLedgerMapper ledgerMapper;

    private static final long STORE_ID = 1L;

    private Member newMember() {
        return memberService.findOrCreate("mock-page-" + System.nanoTime(), STORE_ID);
    }

    private Sku newSku(int stock, String price) {
        Goods g = new Goods();
        g.setStoreId(STORE_ID);
        g.setName("分页测试商品-" + System.nanoTime());
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

    /** 造一笔真实支付过的订单，返回订单号。 */
    private String paidOrder(Member m, Sku sku) {
        MallOrderService.CreateCmd.Item it = new MallOrderService.CreateCmd.Item();
        it.setSkuId(sku.getId());
        it.setQty(1);
        MallOrderService.CreateCmd cmd = new MallOrderService.CreateCmd();
        cmd.setItems(List.of(it));

        OrderVO vo = orderService.create(m.getId(), STORE_ID, cmd);
        MallOrderService.PayCmd pay = new MallOrderService.PayCmd();
        pay.setPayType("BALANCE");
        orderService.pay(m.getId(), vo.getOrderNo(), pay);
        return vo.getOrderNo();
    }

    // ---------- ① 后台退款列表 ----------

    @Test
    void adminRefundListHonoursPageSizeAndReturnsTotals() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("2000"), BigDecimal.ZERO,
                "RC-PAGE-" + System.nanoTime());
        Sku sku = newSku(50, "10.00");

        for (int i = 0; i < 5; i++) {
            RefundService.ApplyCmd apply = new RefundService.ApplyCmd();
            apply.setBizType(RefundApply.BIZ_GOODS);
            apply.setBizNo(paidOrder(m, sku));
            apply.setReason("分页回归第 " + i + " 单");
            refundService.apply(m.getId(), STORE_ID, apply);
        }

        IPage<RefundApply> p1 = refundService.listForAudit(STORE_ID, RefundApply.STATUS_APPLYING, 1, 2);

        assertEquals(2, p1.getRecords().size(), "size=2 必须只返回 2 条：返回更多说明 LIMIT 没拼上");
        assertTrue(p1.getTotal() >= 5, "total 必须被真正 count 出来，恒 0 说明分页插件未注册");
        assertTrue(p1.getPages() >= 3, "pages 应随 total 计算，恒 0 说明分页插件未注册");

        IPage<RefundApply> p2 = refundService.listForAudit(STORE_ID, RefundApply.STATUS_APPLYING, 2, 2);
        assertEquals(2, p2.getRecords().size(), "第 2 页同样受 size 约束");
        assertNotEquals(p1.getRecords().get(0).getId(), p2.getRecords().get(0).getId(),
                "第 2 页必须与第 1 页错开：相同说明 OFFSET 没生效");
    }

    // ---------- ② 后台订单列表 ----------

    @Test
    void adminOrderListHonoursPageSizeAndReturnsTotals() {
        Member m = newMember();
        balanceService.recharge(m.getId(), new BigDecimal("2000"), BigDecimal.ZERO,
                "RC-ORDER-PAGE-" + System.nanoTime());
        Sku sku = newSku(50, "10.00");

        for (int i = 0; i < 4; i++) {
            paidOrder(m, sku);
        }

        IPage<OrderVO> p = orderService.listForAdmin(STORE_ID, null, 1, 3);

        assertEquals(3, p.getRecords().size(), "size=3 必须只返回 3 条");
        assertTrue(p.getTotal() >= 4, "total 必须被真正 count 出来");
    }

    // ---------- ③ 会员余额流水 ----------

    @Test
    void ledgerPageIsBoundedAndCounted() {
        Member m = newMember();
        for (int i = 0; i < 5; i++) {
            balanceService.recharge(m.getId(), new BigDecimal("100"), BigDecimal.ZERO,
                    "RC-LEDGER-" + System.nanoTime());
        }

        LambdaQueryWrapper<BalanceLedger> q = new LambdaQueryWrapper<BalanceLedger>()
                .eq(BalanceLedger::getMemberId, m.getId())
                .orderByDesc(BalanceLedger::getId);

        Page<BalanceLedger> p1 = ledgerMapper.selectPage(new Page<>(1, 2), q);

        assertEquals(2, p1.getRecords().size(), "size=2 必须只返回 2 条");
        assertTrue(p1.getTotal() >= 5, "total 必须被真正 count 出来");

        Page<BalanceLedger> p2 = ledgerMapper.selectPage(new Page<>(2, 2), q);
        assertEquals(2, p2.getRecords().size());
        assertNotEquals(p1.getRecords().get(0).getId(), p2.getRecords().get(0).getId(),
                "第 2 页必须与第 1 页错开");
    }

    // ---------- ④ 插件层兜底上限 ----------

    @Test
    void oversizedPageSizeIsCappedByInterceptor() {
        Member m = newMember();
        // 造满 120 条，才能区分「插件截断到 100」与「恰好只有这么多条」
        for (int i = 0; i < 120; i++) {
            balanceService.recharge(m.getId(), new BigDecimal("10"), BigDecimal.ZERO,
                    "RC-CAP-" + i + "-" + System.nanoTime());
        }

        LambdaQueryWrapper<BalanceLedger> q = new LambdaQueryWrapper<BalanceLedger>()
                .eq(BalanceLedger::getMemberId, m.getId())
                .orderByDesc(BalanceLedger::getId);

        // 即使调用方漏写 size 收敛，插件 maxLimit=100 也要兜住
        Page<BalanceLedger> p = ledgerMapper.selectPage(new Page<>(1, 100_000), q);
        assertEquals(100, p.getRecords().size(),
                "size=100000 必须被插件上限截断到 100，实际 " + p.getRecords().size());
        assertTrue(p.getTotal() >= 120, "total 应反映真实条数，而非被上限截断后的条数");
    }
}
