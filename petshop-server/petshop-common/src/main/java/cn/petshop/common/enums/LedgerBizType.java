package cn.petshop.common.enums;

/**
 * 余额账本业务类型。金额方向由 principalAmt / giftAmt 的正负号表达（账本 append-only）。
 *
 * <ul>
 *   <li>{@link #RECHARGE} —— 充值入账（钱从微信进入余额）</li>
 *   <li>{@link #FREEZE} / {@link #UNFREEZE} —— 冻结额增减，金额恒为 0，仅记录冻结后余额</li>
 *   <li>{@link #CONSUME} —— 消费扣减（负值）</li>
 *   <li>{@link #REFUND} —— 余额出账（钱离开余额并退出账户体系）。<b>当前「退款只入余额」口径下不启用</b>，
 *       保留为将来开通微信原路退款时的落地锚点；其"不得把余额扣成负数"的约束已被 SecurityRegressionTest 锁定。</li>
 *   <li>{@link #REFUND_IN} —— 退款入账（退款金额退回会员可用余额，当前唯一的退款出口）</li>
 *   <li>{@link #ADJUST} —— 门店人工调整</li>
 * </ul>
 */
public enum LedgerBizType {
    RECHARGE, FREEZE, UNFREEZE, CONSUME, REFUND, REFUND_IN, ADJUST
}
