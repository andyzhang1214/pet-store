package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单。
 *
 * 口径：<b>退款只入会员可用余额</b>，不允许退微信 / 退现金。审核通过即到账（同一事务内加款 + 写流水），
 * 因此没有"处理中"的中间态 —— 状态机是 {@code APPLYING → REFUNDED / REJECTED / CANCELLED}，
 * 全部迁移都用 CAS（{@code WHERE status = 前置态}）抢占，避免双击 / 并发审核重复入账。
 *
 * 金额不由客户端决定：{@link #amount} 由服务端按来源业务单据计算（见 RefundService 的 applyCardRefund），
 * 客户端传入的金额一律忽略。
 */
@Data
@TableName("refund_apply")
public class RefundApply {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String refundNo;
    private Long storeId;
    private Long memberId;
    /** 来源业务类型，取值见下方 BIZ_* 常量 */
    private String bizType;
    /** 来源业务单号：CARD 为卡号，MANUAL 为门店侧自拟单号 */
    private String bizNo;
    /** 退款金额（服务端计算） */
    private BigDecimal amount;
    /** 入余额时拆分为本金的部分 */
    private BigDecimal principalAmt;
    /** 入余额时拆分为赠送金的部分 */
    private BigDecimal giftAmt;
    private String reason;
    private String status;
    private String auditor;
    private String auditRemark;
    /**
     * 幂等键（只有店长人工退款会填）。
     *
     * <p>人工退款没有外部业务单号可作幂等依据：单号每次现生成，
     * {@code refundToBalance} 的"按 biz_no 查流水"防重永远不命中，
     * 双击即重复入账。改由调用方给一个一次性的键，靠 {@code uk_refund_idem} 兜底 ——
     * 重放请求撞唯一键时回放首次的结果，不入账。</p>
     */
    private String idempotencyKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 终态时间（通过 / 驳回 / 撤回） */
    private LocalDateTime finishedAt;

    // ---------- 来源业务类型 ----------
    /** 次卡退款：未使用（remain_times = total_times 且无预扣）的次卡按购买实付全额退回余额 */
    public static final String BIZ_CARD = "CARD";
    /** 商品订单退款：待 goods_order 模块落地后接入，当前显式拒绝 */
    public static final String BIZ_GOODS = "GOODS";
    /** 预约相关退款：当前担保只有余额 / 次卡两种，取消走解冻即可，无需退款单 */
    public static final String BIZ_APPOINT = "APPOINT";
    /** 结算多收退款：待结算链路补齐微信渠道后接入 */
    public static final String BIZ_SETTLE = "SETTLE";
    /** 店长人工退款：唯一由后台直接发起并即时生效的来源 */
    public static final String BIZ_MANUAL = "MANUAL";

    // ---------- 状态 ----------
    /** 待审核 */
    public static final String STATUS_APPLYING = "APPLYING";
    /** 已退入余额（终态） */
    public static final String STATUS_REFUNDED = "REFUNDED";
    /** 已驳回（终态） */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 顾客已撤回（终态） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static boolean isFinished(String status) {
        return STATUS_REFUNDED.equals(status) || STATUS_REJECTED.equals(status)
                || STATUS_CANCELLED.equals(status);
    }
}
