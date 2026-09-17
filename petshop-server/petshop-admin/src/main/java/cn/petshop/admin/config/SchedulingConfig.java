package cn.petshop.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 打开 Spring 的定时任务支持。
 *
 * <p>放在启动模块而不是各业务模块：{@code @EnableScheduling} 只应在应用入口出现一次，
 * 一旦被多个模块各自加上，同一个 {@code @Scheduled} 方法会被重复注册、
 * 按 bean 名去重失败时甚至会出现同一任务跑两遍（关单任务重复执行虽然被 CAS 兜住，
 * 但没必要平白多打一倍数据库）。
 *
 * <p>当前只驱动一个任务：{@code OrderExpiryJob}（超时未支付订单关单）。
 * 默认单体单实例调度即可；将来若要多实例并发跑，正确姿势是换用外部调度或加分布式锁，
 * 而不是把 {@code @Scheduled} 的线程池调大。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
