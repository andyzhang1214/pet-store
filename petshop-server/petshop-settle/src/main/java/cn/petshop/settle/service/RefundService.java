package cn.petshop.settle.service;

import cn.petshop.common.util.NoUtil;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.mall.entity.GoodsOrder;
import cn.petshop.mall.service.MallOrderService;
import cn.petshop.settle.event.RefundAppliedEvent;
import cn.petshop.wallet.entity.Card;
import cn.petshop.wallet.entity.RefundApply;
import cn.petshop.wallet.mapper.RefundApplyMapper;
import cn.petshop.wallet.service.BalanceService;
import cn.petshop.wallet.service.CardService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款引擎（口径：<b>退款只入会员可用余额</b>，不退微信 / 不退现金）。
 *
 * <h3>金额由服务端决定</h3>
 * 会员侧只能指定「退哪张单」（bizType + bizNo），<b>不能指定金额</b>。金额一律由服务端按来源业务单据
 * 计算（次卡按购买实付 paid_amount），客户端自报金额会被忽略 —— 这是防止"自己给自己退钱"的关键。
 *
 * <h3>状态机与并发</h3>
 * {@code APPLYING → REFUNDED / REJECTED / CANCELLED}，每条迁移都是 CAS。
 * 审核通过的执行顺序固定为：<b>抢占退款单状态 → 业务侧核销（卡置为 REFUNDED）→ 账务入余额</b>，
 * 三步同一事务，任一步失败整体回滚。并发双击时后到的事务会阻塞在退款单行锁上，
 * 待前者提交后 CAS 返回 0 被拒绝，从根上杜绝重复入账。
 *
 * <h3>审核时重算金额</h3>
 * 申请与审核之间业务状态可能变化（例如卡被用掉了），所以审核时**重新校验来源单据**并按最新可退金额入账，
 * 而不是沿用申请时落库的 amount；不满足退款条件则整单失败并提示刷新重试。
 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final long MAX_PAGE_SIZE = 100;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final RefundApplyMapper refundMapper;
    private final BalanceService balanceService;
    private final CardService cardService;
    private final MallOrderService mallOrderService;
    /** 退款申请落单后通知店长。走事件解耦，见 {@link RefundAppliedEvent} */
    private final ApplicationEventPublisher eventPublisher;

    // ==================== 入参 ====================

    /** 会员侧退款申请：只给"退哪张单"，金额由服务端算。 */
    @Data
    public static class ApplyCmd {
        private String bizType;
        private String bizNo;
        private String reason;
    }

    /**
     * 后台审核入参。
     *
     * <p><b>这里没有操作人字段，是有意的。</b> 改造前后台只有一枚共享令牌，请求里没有身份信息，
     * 于是 operator 只能由前端在请求体里自报 —— 那不是留痕，是让操作者自己声明自己是谁，
     * 任何拿到令牌的人都能把审核人填成别人的名字。现在操作人是 {@code audit} 的独立入参，
     * 由 {@code AdminRefundController} 从登录态取，客户端<b>无法</b>指定。
     */
    @Data
    public static class AuditCmd {
        private Boolean approved;
        private String remark;
    }

    /** 店长人工退款：唯一由后台直接发起并即时生效的来源，用于客诉补偿、结算多收等无法走单据的场景。 */
    @Data
    public static class ManualCmd {
        private Long memberId;
        private BigDecimal amount;
        private String reason;
        /**
         * 幂等键：前端在打开「人工退款」表单时生成一次，重试 / 双击复用同一个值。
         * 不传则为旧行为（无幂等保护），但后台表单一定会传。
         */
        private String idempotencyKey;
    }

    // ==================== 会员侧 ====================

    @Transactional
    public RefundApply apply(Long memberId, Long storeId, ApplyCmd cmd) {
        if (cmd == null || cmd.getBizType() == null || cmd.getBizType().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "退款业务类型不能为空");
        }
        if (cmd.getBizNo() == null || cmd.getBizNo().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "退款业务单号不能为空");
        }
        String bizType = cmd.getBizType().trim().toUpperCase();
        String bizNo = cmd.getBizNo().trim();

        // 金额与可退性以服务端判定为准（顺带完成归属校验：不是本人的单子直接查不到）
        BigDecimal amount = resolveSource(memberId, bizType, bizNo).amount();

        // 同一业务单据不允许同时存在多张未完结 / 已成功的退款单
        Long duplicated = refundMapper.selectCount(new LambdaQueryWrapper<RefundApply>()
                .eq(RefundApply::getBizType, bizType)
                .eq(RefundApply::getBizNo, bizNo)
                .in(RefundApply::getStatus, List.of(RefundApply.STATUS_APPLYING, RefundApply.STATUS_REFUNDED)));
        if (duplicated != null && duplicated > 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该单据已有退款申请，请勿重复提交");
        }

        LocalDateTime now = LocalDateTime.now();
        RefundApply apply = new RefundApply();
        apply.setRefundNo(NoUtil.refundNo());
        apply.setStoreId(storeId);
        apply.setMemberId(memberId);
        apply.setBizType(bizType);
        apply.setBizNo(bizNo);
        apply.setAmount(amount);
        apply.setPrincipalAmt(ZERO);
        apply.setGiftAmt(ZERO);
        apply.setReason(cmd.getReason());
        apply.setStatus(RefundApply.STATUS_APPLYING);
        apply.setCreatedAt(now);
        apply.setUpdatedAt(now);
        refundMapper.insert(apply);

        // 通知店长「有人申请退款」。这里只发布事件，由 admin 层的监听器在**事务提交后**去推送：
        // 推送失败不会连累退款单（事务已提交无法回滚），也不会出现「回滚了却已推送」。
        eventPublisher.publishEvent(new RefundAppliedEvent(
                apply.getRefundNo(), memberId, bizType, bizNo, amount, cmd.getReason()));
        return apply;
    }

    /** 会员撤回自己的待审申请。 */
    @Transactional
    public RefundApply cancel(Long memberId, Long refundId) {
        RefundApply apply = requireOwn(memberId, refundId);
        LocalDateTime now = LocalDateTime.now();
        if (refundMapper.casStatus(apply.getId(), RefundApply.STATUS_APPLYING,
                RefundApply.STATUS_CANCELLED, now) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该退款单已处理，无法撤回");
        }
        apply.setStatus(RefundApply.STATUS_CANCELLED);
        apply.setAuditRemark("顾客主动撤回");
        apply.setFinishedAt(now);
        apply.setUpdatedAt(now);
        return apply;
    }

    public List<RefundApply> listMine(Long memberId) {
        return refundMapper.selectList(new LambdaQueryWrapper<RefundApply>()
                .eq(RefundApply::getMemberId, memberId)
                .orderByDesc(RefundApply::getId));
    }

    // ==================== 后台侧 ====================

    /**
     * 审核退款申请。{@code approved = false} 走驳回，不产生任何资金动作。
     *
     * @param operator 操作人，由调用方从登录态取得（见 {@code AdminContext}），
     *                 不是请求体字段 —— 这是"留痕可信"的前提
     */
    @Transactional
    public RefundApply audit(Long refundId, AuditCmd cmd, String operator) {
        RefundApply apply = refundMapper.selectById(refundId);
        if (apply == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "退款单不存在");
        }
        if (!RefundApply.STATUS_APPLYING.equals(apply.getStatus())) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该退款单已处理，请刷新列表");
        }
        String auditor = requireOperator(operator);
        String remark = cmd == null ? null : cmd.getRemark();
        LocalDateTime now = LocalDateTime.now();

        if (!Boolean.TRUE.equals(cmd.getApproved())) {
            if (remark == null || remark.isBlank()) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "驳回必须填写理由");
            }
            if (refundMapper.casAudit(refundId, RefundApply.STATUS_APPLYING, RefundApply.STATUS_REJECTED,
                    ZERO, ZERO, auditor, remark, now) == 0) {
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该退款单已处理，请刷新列表");
            }
            apply.setStatus(RefundApply.STATUS_REJECTED);
            apply.setAuditor(auditor);
            apply.setAuditRemark(remark);
            apply.setFinishedAt(now);
            return apply;
        }

        // 通过：① 解析来源与金额拆分（兼作"审核时重算"校验）→ ② 抢占状态（重复审核的主防线）
        //      → ③ 业务侧核销（次卡作废 / 订单置退款并回补库存）→ ④ 入余额
        RefundSource src = resolveSource(apply.getMemberId(), apply.getBizType(), apply.getBizNo());
        if (refundMapper.casAudit(refundId, RefundApply.STATUS_APPLYING, RefundApply.STATUS_REFUNDED,
                src.principal(), src.gift(), auditor, remark, now) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该退款单已处理，请刷新列表");
        }
        markSourceRefunded(apply, auditor, remark);
        balanceService.refundToBalance(apply.getMemberId(), src.principal(), src.gift(), apply.getRefundNo(), auditor,
                remark == null ? "退款审核通过" : remark);

        apply.setStatus(RefundApply.STATUS_REFUNDED);
        apply.setAmount(src.amount());
        apply.setPrincipalAmt(src.principal());
        apply.setGiftAmt(src.gift());
        apply.setAuditor(auditor);
        apply.setAuditRemark(remark);
        apply.setFinishedAt(now);
        apply.setUpdatedAt(now);
        return apply;
    }

    /**
     * 店长人工退款：直接生效，不经过"自己审自己"的假审核。
     * 仍落一条 status = REFUNDED 的退款单留痕，与正常退款共用账本入账路径。
     *
     * <p><b>幂等</b>（对应审查 P2-1）：调用方可带 {@code idempotencyKey}。
     * 重放（双击 / 网络重试）时先按 (storeId, key) 查已存在的退款单，命中即原样返回，
     * 不再入账；并发双击则由 {@code uk_refund_idem} 唯一键兜底 ——
     * 后到的事务插入失败，同样回放首次结果。没有幂等键的人工退款就是"点一次退一次"，
     * 靠人眼盯着列表去重是不现实的。</p>
     *
     * @param operator 操作人，由调用方从登录态取得，不接受客户端指定
     */
    @Transactional
    public RefundApply manualRefund(Long storeId, ManualCmd cmd, String operator) {
        if (cmd == null || cmd.getMemberId() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "会员不能为空");
        }
        if (cmd.getAmount() == null || cmd.getAmount().compareTo(ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "退款金额必须大于 0");
        }
        if (cmd.getReason() == null || cmd.getReason().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "人工退款必须填写原因");
        }
        String auditor = requireOperator(operator);
        String idemKey = cmd.getIdempotencyKey() == null || cmd.getIdempotencyKey().isBlank()
                ? null : cmd.getIdempotencyKey().trim();

        // 重放快路径：这个键已经退过款了，直接把首次的结果还回去。
        if (idemKey != null) {
            RefundApply replayed = findByIdemKey(storeId, idemKey);
            if (replayed != null) {
                return replayed;
            }
        }

        String refundNo = NoUtil.refundNo();
        LocalDateTime now = LocalDateTime.now();

        RefundApply apply = new RefundApply();
        apply.setRefundNo(refundNo);
        apply.setStoreId(storeId);
        apply.setMemberId(cmd.getMemberId());
        apply.setBizType(RefundApply.BIZ_MANUAL);
        // 人工退款没有外部业务单号，用退款单号自身占位，保证 biz_no 非空且可反查
        apply.setBizNo(refundNo);
        apply.setAmount(cmd.getAmount());
        apply.setPrincipalAmt(cmd.getAmount());
        apply.setGiftAmt(ZERO);
        apply.setReason(cmd.getReason());
        apply.setStatus(RefundApply.STATUS_REFUNDED);
        apply.setAuditor(auditor);
        apply.setAuditRemark("店长人工退款");
        apply.setIdempotencyKey(idemKey);
        apply.setCreatedAt(now);
        apply.setUpdatedAt(now);
        apply.setFinishedAt(now);
        try {
            refundMapper.insert(apply);
        } catch (DuplicateKeyException e) {
            // 并发双击：另一个事务已用同一个键落了单。回放它的结果，本次不入账。
            // 先插单再入账的顺序是关键 —— 唯一键必须在动钱之前生效。
            RefundApply replayed = idemKey == null ? null : findByIdemKey(storeId, idemKey);
            if (replayed != null) {
                return replayed;
            }
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "退款单号冲突，请重试");
        }

        balanceService.refundToBalance(cmd.getMemberId(), cmd.getAmount(), ZERO, refundNo, auditor, cmd.getReason());
        return apply;
    }

    private RefundApply findByIdemKey(Long storeId, String idemKey) {
        return refundMapper.selectOne(new LambdaQueryWrapper<RefundApply>()
                .eq(RefundApply::getStoreId, storeId)
                .eq(RefundApply::getIdempotencyKey, idemKey)
                .last("LIMIT 1"));
    }

    /**
     * 校验操作人非空。
     *
     * <p>看起来像个多余的检查 —— 操作人不是由服务端注入的吗，怎么会为空？
     * 正因为改成了"服务端注入"，一旦将来有人在新建的调用路径上漏了传参、
     * 或某个内部任务调用了这个方法，传进来的就是 {@code null}，
     * 而 {@code auditor} 会以 null 落库 —— 留痕字段变成空值，
     * 比"填了个假名字"更糟：它看起来像"系统自动"，实际是有人动了手却没人知道是谁。
     * 所以这里宁可抛异常，也不允许生成一条没有操作人的资金记录。
     */
    private String requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "操作人不能为空（资金操作必须留痕）");
        }
        return operator.trim();
    }

    public IPage<RefundApply> listForAudit(Long storeId, String status, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<RefundApply> q = new LambdaQueryWrapper<RefundApply>()
                .eq(RefundApply::getStoreId, storeId)
                .orderByAsc(RefundApply::getId);
        if (status != null && !status.isBlank()) {
            q.eq(RefundApply::getStatus, status.trim().toUpperCase());
        }
        return refundMapper.selectPage(new Page<>(safePage, safeSize), q);
    }

    /**
     * 待审数量。后台角标轮询用 —— 店长不必主动打开列表就能看到「有 N 笔待处理」。
     *
     * <p>直接 count：单店数据量下开销可忽略。门店数量与退款量上来后，
     * 应改为「计数缓存 + 申请/审核时失效」，而不是让每个后台页面都打一次 count。
     */
    public long pendingCount(Long storeId) {
        Long n = refundMapper.selectCount(new LambdaQueryWrapper<RefundApply>()
                .eq(RefundApply::getStoreId, storeId)
                .eq(RefundApply::getStatus, RefundApply.STATUS_APPLYING));
        return n == null ? 0L : n;
    }

    // ==================== 内部 ====================

    private RefundApply requireOwn(Long memberId, Long refundId) {
        RefundApply apply = refundMapper.selectById(refundId);
        if (apply == null || !apply.getMemberId().equals(memberId)) {
            // 越权与不存在返回同一个错误码，避免被用来探测他人单号是否存在
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "退款单不存在");
        }
        return apply;
    }

    /** 退款来源解析结果：可退总额 + 退回余额时的本金 / 赠送金拆分（两者之和恒等于总额）。 */
    private record RefundSource(BigDecimal amount, BigDecimal principal, BigDecimal gift) {
    }

    /**
     * 解析来源单据的可退金额与拆分。已开放线上退款的是<b>次卡</b>与<b>商品订单</b>；
     * 预约取消走担保解冻、无需退款单，结算多收的来源链路尚未补齐，这两类显式拒绝而不是假装支持。
     *
     * <p>拆分口径决定退款入余额后「可退本金」是否失真：
     * <ul>
     *   <li>次卡 —— 全额计本金。购卡的钱是顾客自己的钱，计成赠送金会让后续消费分摊与可退本金显示一起失真。</li>
     *   <li>商品订单 —— 按<b>下单时的原支付构成</b>原样退回（下单从本金扣多少就退多少本金）。
     *       不能用当前余额比例重算，否则两次充值赠送比例不同时会退错。</li>
     * </ul>
     */
    private RefundSource resolveSource(Long memberId, String bizType, String bizNo) {
        if (RefundApply.BIZ_CARD.equals(bizType)) {
            Card card = cardService.getOwnedCard(memberId, bizNo);
            if (!cardService.isRefundable(card)) {
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该次卡已使用或已失效，不支持线上退款");
            }
            BigDecimal paid = card.getPaidAmount();
            if (paid == null || paid.compareTo(ZERO) <= 0) {
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该次卡缺少购买金额记录，请联系门店人工处理");
            }
            return new RefundSource(paid, paid, ZERO);
        }
        if (RefundApply.BIZ_GOODS.equals(bizType)) {
            GoodsOrder order = mallOrderService.requireOwnOrder(memberId, bizNo);
            if (!GoodsOrder.isRefundable(order)) {
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该订单已核销或已退款，不支持线上退款");
            }
            BigDecimal principal = order.getPrincipalPaid() == null ? ZERO : order.getPrincipalPaid();
            BigDecimal gift = order.getGiftPaid() == null ? ZERO : order.getGiftPaid();
            BigDecimal amount = principal.add(gift);
            if (amount.compareTo(ZERO) <= 0) {
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该订单缺少支付金额记录，请联系门店人工处理");
            }
            return new RefundSource(amount, principal, gift);
        }
        if (RefundApply.BIZ_APPOINT.equals(bizType) || RefundApply.BIZ_SETTLE.equals(bizType)) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                    "该类型退款尚未开放线上申请，请联系门店人工处理");
        }
        throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "不支持的退款业务类型：" + bizType);
    }

    /** 退款通过后的业务侧核销。与入余额同事务，任一步失败整体回滚。 */
    private void markSourceRefunded(RefundApply apply, String operator, String remark) {
        if (RefundApply.BIZ_CARD.equals(apply.getBizType())) {
            cardService.markRefunded(apply.getMemberId(), apply.getBizNo(), apply.getRefundNo(),
                    operator, remark == null ? apply.getReason() : remark);
        } else if (RefundApply.BIZ_GOODS.equals(apply.getBizType())) {
            // 订单置 REFUNDED 并回补库存；CAS 失败会抛异常阻断入账
            mallOrderService.markRefunded(apply.getMemberId(), apply.getBizNo(), apply.getRefundNo(), operator);
        }
    }
}
