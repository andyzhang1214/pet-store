package cn.petshop.settle.event;

import java.math.BigDecimal;

/**
 * 退款申请已落单。
 *
 * <p>发布方在事务内 {@code publishEvent}，消费方用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 接收 ——
 * 这样只有退款单真正提交后才推送，不会出现「事务回滚了、店长却已经收到通知」。
 *
 * <p>事件只带推送所需的最小信息（单号、金额、来源、理由），不带实体对象，
 * 避免消费方拿到活引用后误改状态。
 *
 * <p>推送失败绝不能影响退款主流程：消费方必须自行吞掉异常（已提交的事务无法再回滚）。
 */
public record RefundAppliedEvent(
        String refundNo,
        Long memberId,
        String bizType,
        String bizNo,
        BigDecimal amount,
        String reason) {
}
