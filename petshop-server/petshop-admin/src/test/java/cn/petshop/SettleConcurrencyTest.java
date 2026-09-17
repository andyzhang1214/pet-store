package cn.petshop;

import cn.petshop.booking.entity.Appointment;
import cn.petshop.booking.mapper.AppointmentMapper;
import cn.petshop.booking.service.AppointmentService;
import cn.petshop.common.enums.HealthLogType;
import cn.petshop.common.web.BizException;
import cn.petshop.member.entity.Member;
import cn.petshop.member.entity.Pet;
import cn.petshop.member.entity.PetHealthLog;
import cn.petshop.member.mapper.PetHealthLogMapper;
import cn.petshop.member.service.MemberService;
import cn.petshop.member.service.PetService;
import cn.petshop.settle.entity.SettleBill;
import cn.petshop.settle.mapper.SettleBillMapper;
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

/**
 * 结算并发回归（对应审查 M-1 与「测试覆盖评估」建议的最小测试集 ③）。
 * 核心不变量：一次预约最多产生一张结算单、一次扣款、一条健康流水。
 */
@SpringBootTest
@ActiveProfiles("test")
class SettleConcurrencyTest {

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
    private SettleBillMapper settleBillMapper;
    @Autowired
    private PetHealthLogMapper healthLogMapper;

    private Member memberWithBalance(BigDecimal amount) {
        Member m = memberService.findOrCreate("mock-settle-" + System.nanoTime(), 1L);
        balanceService.recharge(m.getId(), amount, BigDecimal.ZERO, "PY-S-" + System.nanoTime());
        return m;
    }

    private Pet cat(Member m) {
        Pet pet = new Pet();
        pet.setName("汤圆");
        pet.setType("CAT");
        pet.setHair("SHORT");
        pet.setWeight(new BigDecimal("4.00"));
        return petService.create(m.getId(), pet);
    }

    /** 建单 → 核销签到，返回已到店的预约 id（使用独立日期避免与其他用例争抢同一时段） */
    private Long arrivedAppt(Member m, Pet pet, int dayOffset) {
        AppointmentService.CreateCmd c = new AppointmentService.CreateCmd();
        c.setServiceId(WASH_SERVICE);
        c.setPetId(pet.getId());
        c.setSlotDate(LocalDate.now().plusDays(dayOffset));
        c.setStartTime(TEN);
        c.setGuaranteeType("BALANCE");
        AppointmentService.ApptVO vo = appointmentService.create(m.getId(), c);
        appointmentService.checkinByVerifyCode(vo.getVerifyCode());
        return vo.getId();
    }

    /** 操作人由调用方传入（生产环境来自登录态），不再放进请求体 */
    private static final String OPERATOR = "店长小爪";

    private SettleService.ConfirmCmd confirmCmd(String amount) {
        SettleService.ConfirmItem item = new SettleService.ConfirmItem();
        item.setName("基础洗护套餐");
        item.setCategory("WASH");
        item.setAmount(new BigDecimal(amount));
        SettleService.ConfirmCmd cmd = new SettleService.ConfirmCmd();
        cmd.setItems(List.of(item));
        cmd.setPayType("BALANCE");
        return cmd;
    }

    private long billCount(Long apptId) {
        return settleBillMapper.selectCount(new LambdaQueryWrapper<SettleBill>()
                .eq(SettleBill::getApptId, apptId));
    }

    /** 顺序双击：第二次必须因状态 CAS 失败被拒绝，不能再扣一次钱。 */
    @Test
    void secondConfirmOnSameAppointmentIsRejected() {
        Member m = memberWithBalance(new BigDecimal("300"));
        Pet pet = cat(m);
        Long apptId = arrivedAppt(m, pet, 5);

        SettleService.ConfirmResult first = settleService.confirm(apptId, confirmCmd("120.00"), OPERATOR);
        assertEquals(0, new BigDecimal("120.00").compareTo(first.getTotal()));

        assertThrows(BizException.class, () -> settleService.confirm(apptId, confirmCmd("120.00"), OPERATOR));

        assertEquals(1, billCount(apptId), "同一预约只能有一张结算单");
        WalletVO wallet = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("180").compareTo(wallet.getBalance()), "300 - 120 = 180，只能扣一次");
        assertEquals(cn.petshop.common.enums.ApptStatus.SETTLED.name(),
                apptMapper.selectById(apptId).getStatus());
    }

    /** 真并发双击：两个线程同时 confirm，只能有一个成功。 */
    @Test
    void concurrentConfirmOnlyOneSucceeds() throws Exception {
        Member m = memberWithBalance(new BigDecimal("300"));
        Pet pet = cat(m);
        Long apptId = arrivedAppt(m, pet, 6);

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
                        settleService.confirm(apptId, confirmCmd("120.00"), OPERATOR);
                        success.incrementAndGet();
                    } catch (BizException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        rejected.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发结算未在 30s 内结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, success.get(), "并发双击只能有一次结算成功");
        assertEquals(1, rejected.get(), "另一次应被状态 CAS 拒绝");
        assertEquals(1, billCount(apptId), "并发双击不能产生两张结算单");

        WalletVO wallet = balanceService.wallet(m.getId());
        assertEquals(0, new BigDecimal("180").compareTo(wallet.getBalance()), "并发双击只能扣一次 120");

        long bathLogs = healthLogMapper.selectCount(new LambdaQueryWrapper<PetHealthLog>()
                .eq(PetHealthLog::getPetId, pet.getId())
                .eq(PetHealthLog::getLogType, HealthLogType.BATH));
        assertEquals(1, bathLogs, "健康流水也只能写一条");
    }
}
