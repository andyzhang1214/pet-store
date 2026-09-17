package cn.petshop.booking.job;

import cn.petshop.booking.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 逾期未确认预约的自动到店兜底。
 *
 * <h3>为什么必须有这个任务</h3>
 * 自助确认只允许在预约当天点（见 {@code AppointmentMapper.casArrivedByIdAndMember}）。
 * 如果顾客当天忘了点、店员也忘了扫码核销，这张预约就会永远停在 PENDING：
 * 担保金一直冻着，报表上的「待到店」越积越多。本任务在预约日次日起把仍未确认的
 * 预约自动置为 ARRIVED，让单据闭环到结算环节。
 *
 * <h3>与「标记未到」的取舍</h3>
 * 爽约处理（NO_SHOW，按门店政策扣/退担保）的窗口因此只有预约当天；次日起一律视为
 * 已到店、走正常结算。这是产品上的明确取舍 —— 宁可默认顾客来过，也不让担保无限期冻结。
 *
 * <h3>并发与批量</h3>
 * 与商城关单任务同理：多实例同时扫到同一批预约时，{@code casArrivedById}
 * （WHERE status='PENDING'）保证只有一个节点改成功，不需要分布式锁。
 * 单轮扫描条数有上限，长时间停机后的积压由后续轮次消化。
 */
@Component
@RequiredArgsConstructor
public class AppointmentAutoConfirmJob {

    private static final Logger log = LoggerFactory.getLogger(AppointmentAutoConfirmJob.class);

    private final AppointmentService appointmentService;

    /** 单轮扫描上限。 */
    @Value("${petshop.booking.auto-confirm-batch-size:200}")
    private int batchSize;

    /** 开关。关闭后任务空转直接返回，便于在不需要兜底的环境里停掉它。 */
    @Value("${petshop.booking.auto-confirm-enabled:true}")
    private boolean enabled;

    /** 每 5 分钟扫一轮：单店规模下预约量小，及时性够用，代价可忽略。 */
    @Scheduled(
            fixedDelayString = "${petshop.booking.auto-confirm-scan-ms:300000}",
            initialDelayString = "${petshop.booking.auto-confirm-initial-delay-ms:30000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int confirmed = confirmOverdue(batchSize);
            if (confirmed > 0) {
                log.info("自动确认到店：本轮将 {} 笔逾期未确认预约置为已到店", confirmed);
            }
        } catch (Exception e) {
            log.error("自动确认到店执行失败，将于下一轮重试", e);
        }
    }

    /**
     * 扫描主体，返回本轮实际确认的笔数。方法保持 public 是为了让测试能显式触发一轮，
     * 不必真的等定时器 ——「只能靠等」的定时任务测试要么很慢要么很脆。
     */
    public int confirmOverdue(int batchSize) {
        LocalDate today = LocalDate.now();
        List<Long> ids = appointmentService.listPendingIdsBefore(today, batchSize);
        int confirmed = 0;
        for (Long id : ids) {
            try {
                if (appointmentService.autoConfirmById(id)) {
                    confirmed++;
                }
            } catch (Exception e) {
                // 单笔失败不影响同批其余预约：这一单留到下一轮再试
                log.warn("预约 {} 自动确认失败，跳过：{}", id, e.getMessage());
            }
        }
        return confirmed;
    }
}
