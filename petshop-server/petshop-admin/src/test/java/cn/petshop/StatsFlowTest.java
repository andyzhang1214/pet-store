package cn.petshop;

import cn.petshop.admin.stats.StatisticsService;
import cn.petshop.booking.entity.Appointment;
import cn.petshop.booking.entity.TimeSlot;
import cn.petshop.booking.mapper.AppointmentMapper;
import cn.petshop.booking.mapper.TimeSlotMapper;
import cn.petshop.booking.service.AppointmentService;
import cn.petshop.mall.entity.Goods;
import cn.petshop.mall.entity.Sku;
import cn.petshop.mall.mapper.GoodsMapper;
import cn.petshop.mall.mapper.SkuMapper;
import cn.petshop.mall.service.MallOrderService;
import cn.petshop.mall.service.OrderVO;
import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.service.MemberService;
import cn.petshop.member.service.PetService;
import cn.petshop.settle.service.SettleService;
import cn.petshop.wallet.service.BalanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据统计口径回归。
 *
 * <p>统计页最怕的不是查不出数，而是<b>口径悄悄漂移</b>：服务营收把退款也算进去、
 * 商品营收把未支付单算成收入、趋势序列缺日补零失效导致柱子错位。这里用一条真实的
 * 「充值 → 预约 → 核销 → 结算 → 商品下单 → 支付」链路造出当天业务数据，
 * 再断言 overview 的每个口径都包含了这条链路产生的金额 —— 不要求精确相等
 * （H2 测试库被全 suite 共享，其他用例也会产生当日流水），只要求下限成立。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class StatsFlowTest {

    private static final long WASH_SERVICE = 101L;
    private static final String OPERATOR = "店长统计测试";

    @Autowired
    private StatisticsService statisticsService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private PetService petService;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private SettleService settleService;
    @Autowired
    private MallOrderService orderService;
    @Autowired
    private GoodsMapper goodsMapper;
    @Autowired
    private SkuMapper skuMapper;
    @Autowired
    private AppointmentMapper apptMapper;
    @Autowired
    private TimeSlotMapper timeSlotMapper;

    private BigDecimal big(String v) {
        return new BigDecimal(v);
    }

    @Test
    void overviewReflectsTodayBusiness() {
        // ---------- 造数：充值 300 → 预约今天 17:00 → 核销 → 结算 120 → 商品单 50 ----------
        Member m = memberService.findOrCreate("mock-stats-" + System.nanoTime(), 1L);
        balanceService.recharge(m.getId(), big("300"), BigDecimal.ZERO, "RC-S-" + System.nanoTime());

        Pet pet = new Pet();
        pet.setName("统计团子-" + System.nanoTime());
        pet.setType("CAT");
        pet.setHair("SHORT");
        pet.setWeight(big("4.00"));
        pet = petService.create(m.getId(), pet);

        LocalDate today = LocalDate.now();

        // 时段先建在明天（独立时刻 17:00，避免与其他用例共享 slot 行），再挪到今天。
        // 不能直接把 slotDate 写成 today：create() 会拒绝已过去的时段（对应审查 P3-6），
        // 那样这个用例只在 16:00 之前跑得通，一过点就变成随机失败。
        AppointmentService.CreateCmd cmd = new AppointmentService.CreateCmd();
        cmd.setServiceId(WASH_SERVICE);
        cmd.setPetId(pet.getId());
        cmd.setSlotDate(today.plusDays(1));
        cmd.setStartTime(LocalTime.of(17, 0));
        cmd.setGuaranteeType("BALANCE");
        AppointmentService.ApptVO appt = appointmentService.create(m.getId(), cmd);
        assertEquals("PENDING", appt.getStatus());

        Appointment apptRow = apptMapper.selectById(appt.getId());
        TimeSlot slot = timeSlotMapper.selectById(apptRow.getSlotId());
        slot.setSlotDate(today);
        timeSlotMapper.updateById(slot);

        appointmentService.checkinByVerifyCode(appt.getVerifyCode());

        SettleService.ConfirmCmd confirm = new SettleService.ConfirmCmd();
        SettleService.ConfirmItem item = new SettleService.ConfirmItem();
        item.setName("精致洗护");
        item.setCategory("WASH");
        item.setAmount(big("120"));
        confirm.setItems(List.of(item));
        confirm.setPayType("BALANCE");
        SettleService.ConfirmResult bill = settleService.confirm(appt.getId(), confirm, OPERATOR);
        assertEquals(0, big("120.00").compareTo(bill.getTotal()), "结算金额由服务端按价目重算");

        Goods g = new Goods();
        g.setStoreId(1L);
        g.setName("统计测试商品-" + System.nanoTime());
        g.setCategory("洗护");
        g.setStatus(Goods.STATUS_ON_SALE);
        goodsMapper.insert(g);
        Sku sku = new Sku();
        sku.setGoodsId(g.getId());
        sku.setName("标准装");
        sku.setPrice(big("50.00"));
        sku.setStock(5);
        skuMapper.insert(sku);

        MallOrderService.CreateCmd.Item it = new MallOrderService.CreateCmd.Item();
        it.setSkuId(sku.getId());
        it.setQty(1);
        MallOrderService.CreateCmd orderCmd = new MallOrderService.CreateCmd();
        orderCmd.setItems(List.of(it));
        OrderVO created = orderService.create(m.getId(), 1L, orderCmd);
        OrderVO paid = orderService.pay(m.getId(), created.getOrderNo(), null);
        assertEquals("PAID", paid.getStatus());

        // ---------- 断言口径 ----------
        StatisticsService.Overview ov = statisticsService.overview(7);

        // 服务营收包含这单结算（>=，因为测试库当天可能还有其他用例的结算单）
        assertTrue(ov.getToday().getServiceRevenue().compareTo(big("120")) >= 0,
                "服务营收应包含今日结算单 " + bill.getTotal());
        assertTrue(ov.getToday().getServiceCount() >= 1);

        // 商品营收包含这笔已支付订单；CREATED 未支付的不算
        assertTrue(ov.getToday().getGoodsRevenue().compareTo(big("50")) >= 0,
                "商品营收应包含今日已支付订单");

        // 总营收 = 服务 + 商品
        assertEquals(0,
                ov.getToday().getServiceRevenue().add(ov.getToday().getGoodsRevenue())
                        .compareTo(ov.getToday().getTotalRevenue()),
                "总营收口径必须等于服务 + 商品，前端不得自行加总");

        // 充值：本用例充了 300 实充、0 赠送
        assertTrue(ov.getToday().getRechargePrincipal().compareTo(big("300")) >= 0);
        assertTrue(ov.getToday().getRechargeCount() >= 1);

        // 今日预约：这单已结算
        assertTrue(ov.getToday().getApptTotal() >= 1);
        assertTrue(ov.getToday().getApptByStatus().getOrDefault("SETTLED", 0L) >= 1,
                "今日预约分布应包含 SETTLED 状态");

        // 会员资产
        assertTrue(ov.getMembers().getTotal() >= 1);
        assertTrue(ov.getMembers().getBalanceTotal().compareTo(BigDecimal.ZERO) >= 0);

        // 趋势：恒为 7 个点（含今天），缺数据日补零；今天的两个口径都要能对上
        assertEquals(7, ov.getTrend().size(), "趋势序列必须恒为 7 个点，缺数据日补零");
        StatisticsService.TrendDay todayTrend = ov.getTrend().get(ov.getTrend().size() - 1);
        assertTrue(todayTrend.getService().compareTo(big("120")) >= 0);
        assertTrue(todayTrend.getGoods().compareTo(big("50")) >= 0);
    }
}
