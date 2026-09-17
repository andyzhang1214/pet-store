package cn.petshop.mall.job;

import cn.petshop.mall.service.MallOrderService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时未支付订单的自动关单。
 *
 * <h3>为什么必须有这个任务</h3>
 * 下单即锁库存（见 {@code MallOrderService.create}），这是为了不让顾客走到付款才被告知没货。
 * 代价是：顾客下完单随手关掉小程序，那部分库存就被一张永远不付款的单占住了。
 * 没有关单任务时，这些库存只能靠人工去数据库捞出来改状态 —— 这不是"少个功能"，是库存会慢性泄漏。
 *
 * <h3>为什么不加分布式锁</h3>
 * 多实例部署时多个节点会同时扫到同一批订单，但关单动作落在
 * {@code GoodsOrderMapper.casExpire}（{@code WHERE status='CREATED' AND created_at < deadline}）上：
 * 数据库行锁下只有一个节点能改成功，另一个拿到 0 直接跳过，<b>不会重复回补库存</b>。
 * 也就是说并发安全由数据层兜住，节点数不影响正确性，只是多几次无谓的 UPDATE。
 * 单店规模下这比引入 Redis 分布式锁更简单，也不会因为锁服务抖动而让关单停摆。
 *
 * <h3>每轮只做有限的活</h3>
 * 单轮扫描条数有上限（{@code expiry-batch-size}），积压时靠下一轮继续消化。
 * 不用"一直循环到没有超时单为止"的写法 —— 那样在长时间停机后重启的瞬间会发起一次
 * 无界的批量写，把库的连接和锁都占满。
 */
@Component
@RequiredArgsConstructor
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final MallOrderService orderService;

    /** 关单阈值（分钟）。PRD 6.2 定义为 15 分钟未支付即关闭。 */
    @Value("${petshop.mall.order-timeout-minutes:15}")
    private int timeoutMinutes;

    /** 单轮扫描上限。 */
    @Value("${petshop.mall.expiry-batch-size:200}")
    private int batchSize;

    /** 开关。关闭后任务只是空转直接返回，便于在不需要自动关单的环境里停掉它。 */
    @Value("${petshop.mall.expiry-enabled:true}")
    private boolean enabled;

    /**
     * 扫描并关闭超时订单，返回本轮实际关闭的笔数。
     *
     * <p>方法保持 {@code public} 且不依赖调度器，是为了让测试与运维都能<b>显式触发一轮</b>，
     * 而不必去等定时器 —— 定时任务最容易写出"只能靠等"的测试，那种测试要么很慢要么很脆。
     *
     * <p>整个方法体包在 try/catch 里：数据库抖一下不应该让这个 Bean 的后续轮次受影响，
     * 更不应该把异常抛到调度线程上。失败只记日志，下一轮自然会重试。
     */
    @Scheduled(
            fixedDelayString = "${petshop.mall.expiry-scan-ms:60000}",
            initialDelayString = "${petshop.mall.expiry-initial-delay-ms:30000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        if (timeoutMinutes <= 0) {
            log.warn("petshop.mall.order-timeout-minutes={} 不合法，本轮跳过关单", timeoutMinutes);
            return;
        }
        try {
            int closed = expire(timeoutMinutes, batchSize);
            if (closed > 0) {
                log.info("超时关单：本轮关闭 {} 笔未支付订单（阈值 {} 分钟），库存已回补",
                        closed, timeoutMinutes);
            }
        } catch (Exception e) {
            log.error("超时关单执行失败，将于下一轮重试", e);
        }
    }

    /**
     * 关单主体。{@code deadline} 只在扫描时算一次并贯穿整轮，
     * 保证「筛出来的」和「CAS 复核的」用的是同一个时间点。
     */
    public int expire(int timeoutMinutes, int batchSize) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<String> orderNos = orderService.findExpiredOrderNos(deadline, batchSize);
        int closed = 0;
        for (String orderNo : orderNos) {
            try {
                if (orderService.closeExpiredOrder(orderNo, deadline)) {
                    closed++;
                }
            } catch (Exception e) {
                // 单笔失败不影响同批其余订单：这一单留到下一轮再试，其余照常推进
                log.warn("订单 {} 关单失败，跳过：{}", orderNo, e.getMessage());
            }
        }
        return closed;
    }

    /** 供后台「手动触发一轮」复用既定阈值，避免同一条规则在两处各写一个默认值。 */
    public int timeoutMinutesOrDefault() {
        return timeoutMinutes;
    }

    /** 同上，供手动触发复用单轮扫描上限。 */
    public int batchSizeOrDefault() {
        return batchSize;
    }
}
