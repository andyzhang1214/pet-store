package cn.petshop.admin.notify.listener;

import cn.petshop.admin.notify.service.WxSubscribeService;
import cn.petshop.settle.event.RefundAppliedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 退款申请落单后通知店长。
 *
 * <p>监听 {@code AFTER_COMMIT}：只有退款单真正提交后才推送。若监听事务内的事件，
 * 一旦事务回滚，店长就会收到一条并不存在的退款通知。
 *
 * <p>推送失败绝不影响退款主流程 —— 事务已提交，无法回滚，抛异常只会把异常带给
 * 一个已经成功的调用方。这里做双保险捕获（{@link WxSubscribeService} 内部已全捕获）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundNotifyListener {

    private final WxSubscribeService wxSubscribeService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundApplied(RefundAppliedEvent event) {
        try {
            wxSubscribeService.notifyRefundApplied(event);
        } catch (Exception e) {
            log.warn("[退款提醒] 推送出现未预期异常，已忽略 refundNo={}", event.refundNo(), e);
        }
    }
}
