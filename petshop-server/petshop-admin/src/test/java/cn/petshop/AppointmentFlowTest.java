package cn.petshop;

import cn.petshop.booking.entity.Appointment;
import cn.petshop.booking.entity.StoreConfig;
import cn.petshop.booking.entity.TimeSlot;
import cn.petshop.booking.job.AppointmentAutoConfirmJob;
import cn.petshop.booking.mapper.AppointmentMapper;
import cn.petshop.booking.mapper.StoreConfigMapper;
import cn.petshop.booking.mapper.TimeSlotMapper;
import cn.petshop.booking.service.AppointmentService;
import cn.petshop.booking.service.SlotService;
import cn.petshop.common.enums.ApptStatus;
import cn.petshop.common.enums.HealthLogType;
import cn.petshop.common.web.BizException;
import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.entity.PetHealthLog;
import cn.petshop.member.mapper.PetHealthLogMapper;
import cn.petshop.member.service.MemberService;
import cn.petshop.member.service.PetService;
import cn.petshop.settle.service.SettleService;
import cn.petshop.wallet.service.BalanceService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AppointmentFlowTest {

    private static final long WASH_SERVICE = 101L;
    private static final LocalTime TEN = LocalTime.of(10, 0);

    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private SettleService settleService;
    @Autowired
    private BalanceService balanceService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private PetService petService;
    @Autowired
    private AppointmentMapper apptMapper;
    @Autowired
    private PetHealthLogMapper healthLogMapper;
    @Autowired
    private SlotService slotService;
    @Autowired
    private TimeSlotMapper timeSlotMapper;
    @Autowired
    private StoreConfigMapper storeConfigMapper;
    @Autowired
    private AppointmentAutoConfirmJob autoConfirmJob;

    /** 结算操作人。生产环境由 Controller 从登录态注入，测试里直接传入以便断言留痕。 */
    private static final String OPERATOR = "店长小爪";

    private Member memberWithBalance(BigDecimal amount) {
        Member m = memberService.findOrCreate("mock-appt-" + System.nanoTime(), 1L);
        balanceService.recharge(m.getId(), amount, BigDecimal.ZERO, "PY-T-" + System.nanoTime());
        return m;
    }

    private Pet cat(Member m) {
        Pet pet = new Pet();
        pet.setName("团子");
        pet.setType("CAT");
        pet.setHair("SHORT");
        pet.setWeight(new BigDecimal("4.00"));
        return petService.create(m.getId(), pet);
    }

    // 各测试方法用不同日期，避免共享 H2 库时互相占用同一时段行
    private AppointmentService.CreateCmd cmd(Long petId, String guarantee, int dayOffset) {
        AppointmentService.CreateCmd c = new AppointmentService.CreateCmd();
        c.setServiceId(WASH_SERVICE);
        c.setPetId(petId);
        c.setSlotDate(LocalDate.now().plusDays(dayOffset));
        c.setStartTime(TEN);
        c.setGuaranteeType(guarantee);
        return c;
    }

    @Test
    void fullChainCreateCancelCheckinSettle() {
        Member m = memberWithBalance(new BigDecimal("300"));
        Pet pet = cat(m);

        LocalDate day1 = LocalDate.now().plusDays(1);
        AppointmentService.ApptVO vo = appointmentService.create(m.getId(), cmd(pet.getId(), "BALANCE", 1));
        assertEquals(ApptStatus.PENDING.name(), vo.getStatus());
        assertEquals(0, new BigDecimal("120.00").compareTo(vo.getPrice()));
        assertNotNull(vo.getVerifyCode());
        assertEquals(8, vo.getVerifyCode().length());

        WalletVO wallet = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("120").compareTo(wallet.getFrozenAmount()));

        List<SlotService.SlotVO> slots = slotService.availableSlots(1L, WASH_SERVICE, day1);
        SlotService.SlotVO ten = slots.stream().filter(s -> s.getStartTime().equals("10:00")).findFirst().orElseThrow();
        assertEquals(2, ten.getRemaining(), "容量 3 预约 1 只后应剩 2");

        appointmentService.cancel(m.getId(), vo.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceService.wallet(m.getId()).getFrozenAmount()));
        assertEquals(ApptStatus.CANCELLED.name(), apptMapper.selectById(vo.getId()).getStatus());

        AppointmentService.ApptVO vo2 = appointmentService.create(m.getId(), cmd(pet.getId(), "BALANCE", 1));
        appointmentService.checkinByVerifyCode(vo2.getVerifyCode());
        assertEquals(ApptStatus.ARRIVED.name(), apptMapper.selectById(vo2.getId()).getStatus());

        SettleService.ConfirmCmd confirm = new SettleService.ConfirmCmd();
        SettleService.ConfirmItem item = new SettleService.ConfirmItem();
        item.setName("基础洗护套餐");
        item.setCategory("WASH");
        item.setAmount(new BigDecimal("120.00"));
        confirm.setItems(List.of(item));
        confirm.setPayType("BALANCE");
        SettleService.ConfirmResult result = settleService.confirm(vo2.getId(), confirm, OPERATOR);

        assertEquals(0, new BigDecimal("120.00").compareTo(result.getTotal()));
        assertEquals(ApptStatus.SETTLED.name(), apptMapper.selectById(vo2.getId()).getStatus());
        WalletVO after = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("180").compareTo(after.getBalance()), "300 - 120 = 180");
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getFrozenAmount()));

        List<PetHealthLog> bathLogs = healthLogMapper.selectList(new LambdaQueryWrapper<PetHealthLog>()
                .eq(PetHealthLog::getPetId, pet.getId())
                .eq(PetHealthLog::getLogType, HealthLogType.BATH));
        assertEquals(1, bathLogs.size(), "结算应同事务写入洗澡健康流水");
        assertEquals(PetHealthLog.SOURCE_SERVICE, bathLogs.get(0).getSource());
        assertEquals("店长小爪", bathLogs.get(0).getOperator());
    }

    @Test
    void checkinByUsedCodeFails() {
        Member m = memberWithBalance(new BigDecimal("200"));
        Pet pet = cat(m);
        AppointmentService.ApptVO vo = appointmentService.create(m.getId(), cmd(pet.getId(), "BALANCE", 2));
        appointmentService.checkinByVerifyCode(vo.getVerifyCode());
        assertThrows(BizException.class, () -> appointmentService.checkinByVerifyCode(vo.getVerifyCode()));
    }

    @Test
    void noGuaranteeRejectedWhenDisabled() {
        Member m = memberWithBalance(new BigDecimal("200"));
        Pet pet = cat(m);
        assertThrows(BizException.class, () -> appointmentService.create(m.getId(), cmd(pet.getId(), "NONE", 3)));
    }

    @Test
    void settleRejectsPendingAppointment() {
        Member m = memberWithBalance(new BigDecimal("200"));
        Pet pet = cat(m);
        AppointmentService.ApptVO vo = appointmentService.create(m.getId(), cmd(pet.getId(), "BALANCE", 4));
        SettleService.ConfirmCmd confirm = new SettleService.ConfirmCmd();
        SettleService.ConfirmItem item = new SettleService.ConfirmItem();
        item.setName("基础洗护套餐");
        item.setCategory("WASH");
        item.setAmount(new BigDecimal("120"));
        confirm.setItems(List.of(item));
        assertThrows(BizException.class, () -> settleService.confirm(vo.getId(), confirm, OPERATOR));
    }

    /**
     * 把某张预约的时段行改到指定日期，用来构造「当天 / 昨天」这类 create() 走不到的日期。
     *
     * <p>为什么必须这么绕：{@code create()} 会拒绝已过去的时段（对应审查 P3-6），
     * 所以「今天 10:00」在 10:00 之后就建不出来了 —— 直接把 {@code dayOffset=0} 写死在用例里，
     * 等于让测试只在上午跑得通，下午必挂。先建在未来再把时段挪到目标日期，才与时钟无关。
     */
    private void moveSlotTo(Long apptId, LocalDate date) {
        Appointment appt = apptMapper.selectById(apptId);
        TimeSlot slot = timeSlotMapper.selectById(appt.getSlotId());
        slot.setSlotDate(date);
        timeSlotMapper.updateById(slot);
    }

    @Test
    void selfConfirmArrivalOnAppointmentDay() {
        Member m = memberWithBalance(new BigDecimal("300"));
        Pet pet = cat(m);
        // 13:00 是独立时段，只在本用例出现，挪日期不会误伤同时段的其他预约
        AppointmentService.CreateCmd c = cmd(pet.getId(), "BALANCE", 1);
        c.setStartTime(LocalTime.of(13, 0));
        AppointmentService.ApptVO vo = appointmentService.create(m.getId(), c);
        moveSlotTo(vo.getId(), LocalDate.now());

        appointmentService.confirmArrival(m.getId(), vo.getId());
        assertEquals(ApptStatus.ARRIVED.name(), apptMapper.selectById(vo.getId()).getStatus());
        // 与扫码核销同规则：确认后担保仍冻结，结算仍由后台发起
        assertEquals(0, new BigDecimal("120").compareTo(balanceService.wallet(m.getId()).getFrozenAmount()));
        // 重复确认被 CAS 拦截
        assertThrows(BizException.class, () -> appointmentService.confirmArrival(m.getId(), vo.getId()));
    }

    @Test
    void selfConfirmRejectsNonOwnerAndFutureDate() {
        Member m = memberWithBalance(new BigDecimal("300"));
        Member other = memberWithBalance(new BigDecimal("100"));
        Pet pet = cat(m);
        // 用独立日期（offset 5），避免与其他用例共享同一时段行（时段容量会被占掉）
        AppointmentService.ApptVO vo = appointmentService.create(m.getId(), cmd(pet.getId(), "BALANCE", 5));

        assertThrows(BizException.class, () -> appointmentService.confirmArrival(other.getId(), vo.getId()),
                "不能确认别人的预约");
        assertThrows(BizException.class, () -> appointmentService.confirmArrival(m.getId(), vo.getId()),
                "预约当天才能自助确认");
        assertEquals(ApptStatus.PENDING.name(), apptMapper.selectById(vo.getId()).getStatus(),
                "被拒绝的确认不得改动状态");
    }

    @Test
    void autoConfirmJobSweepsOverduePendingOnly() {
        Member m = memberWithBalance(new BigDecimal("400"));
        Pet petA = cat(m);
        Pet petB = cat(m);

        // 两张单都先建在未来（create 拒绝过去时段），再各自把时段挪到目标日期。
        // todayAppt 用 12:00、overdue 用 11:00：time_slot 在
        // (store_id, slot_date, start_time, service_id) 上有唯一索引，
        // 挪日期时若撞上其他用例已经占下的同一时刻，会直接报 DuplicateKey。
        AppointmentService.CreateCmd todayCmd = cmd(petA.getId(), "BALANCE", 2);
        todayCmd.setStartTime(LocalTime.of(12, 0));
        AppointmentService.ApptVO todayAppt = appointmentService.create(m.getId(), todayCmd);

        AppointmentService.CreateCmd overdueCmd = cmd(petB.getId(), "BALANCE", 2);
        overdueCmd.setStartTime(LocalTime.of(11, 0));
        AppointmentService.ApptVO overdue = appointmentService.create(m.getId(), overdueCmd);

        moveSlotTo(todayAppt.getId(), LocalDate.now());
        moveSlotTo(overdue.getId(), LocalDate.now().minusDays(1));

        autoConfirmJob.confirmOverdue(200);

        assertEquals(ApptStatus.ARRIVED.name(), apptMapper.selectById(overdue.getId()).getStatus(),
                "逾期未确认的预约应被自动置为已到店");
        assertEquals(ApptStatus.PENDING.name(), apptMapper.selectById(todayAppt.getId()).getStatus(),
                "当天的预约不得被自动确认");
    }

    /**
     * NONE 担保并发双取消：同一时段的名额只能减一次（对应审查 P1-4 与「建议补的用例 ②」）。
     *
     * <p>为什么必须放两张在途预约：{@code decrBooked} 的 SQL 带 {@code AND booked_count > 0} 守卫，
     * 时段里只有 1 张单时「减两次」会被守卫吃掉（1→0→0），缺陷被掩盖。真实伤害出现在
     * {@code booked_count = 2} 时：非 CAS 写法下两个事务都读到 PENDING，各减一次变成 0，
     * 而其中一张预约还在，可用名额就比实际多出一个 —— 时段超容。
     *
     * <p>NONE 担保是这条路径最容易失守的分支：{@code releaseGuarantee} 是空操作，
     * 没有任何"条件更新碰巧失败"来兜底（BALANCE/CARD 靠 unfreeze/release 的条件更新侥幸安全）。
     */
    @Test
    void concurrentCancelOnNoneGuaranteeDecrementsSlotOnce() throws Exception {
        StoreConfig cfg = storeConfigMapper.selectById(1L);
        boolean original = Boolean.TRUE.equals(cfg.getNoGuaranteeEnabled());
        cfg.setNoGuaranteeEnabled(true);
        storeConfigMapper.updateById(cfg);
        try {
            Member m = memberWithBalance(new BigDecimal("300"));
            Pet petA = cat(m);
            Pet petB = cat(m);

            // 独立时段（offset 12 / 14:00），不与其它用例争抢 slot 行
            AppointmentService.CreateCmd cA = cmd(petA.getId(), "NONE", 12);
            cA.setStartTime(LocalTime.of(14, 0));
            AppointmentService.CreateCmd cB = cmd(petB.getId(), "NONE", 12);
            cB.setStartTime(LocalTime.of(14, 0));
            AppointmentService.ApptVO voA = appointmentService.create(m.getId(), cA);
            appointmentService.create(m.getId(), cB);

            Appointment apptA = apptMapper.selectById(voA.getId());
            Long slotId = apptA.getSlotId();
            assertEquals(2, timeSlotMapper.selectById(slotId).getBookedCount(),
                    "同一时段两张在途预约，占用应为 2 —— 只有 1 张时 decrBooked 的 >0 守卫会掩盖重复递减");

            AtomicInteger success = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 2; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            appointmentService.cancel(m.getId(), voA.getId());
                            success.incrementAndGet();
                        } catch (Exception e) {
                            rejected.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "并发取消未在 30s 内结束");
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, success.get(), "并发双取消只能有一次成功");
            assertEquals(1, rejected.get(), "另一次应被状态 CAS 拒绝");
            assertEquals(1, timeSlotMapper.selectById(slotId).getBookedCount(),
                    "只取消了 A，名额应停在 1；减到 0 就说明重复 decr 让时段超容");
            assertEquals(ApptStatus.CANCELLED.name(), apptMapper.selectById(voA.getId()).getStatus());
            assertEquals(0, BigDecimal.ZERO.compareTo(balanceService.wallet(m.getId()).getFrozenAmount()),
                    "NONE 担保本就不冻结余额");
        } finally {
            // 恢复默认，避免污染共享 H2 库里的其它用例（如 noGuaranteeRejectedWhenDisabled）
            cfg.setNoGuaranteeEnabled(original);
            storeConfigMapper.updateById(cfg);
        }
    }
}
