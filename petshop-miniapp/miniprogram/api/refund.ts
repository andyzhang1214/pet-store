import { get, post } from '../utils/request'
import type { SnowflakeId } from '../utils/request'

/**
 * 退款单。口径是「退款只入可用余额」——审核通过即到账，没有"处理中"的中间态。
 * 字段与后端 RefundApply 实体严格一致（别再自己发明字段名，M-2 那次契约断裂就是这么来的）。
 * 注意 id 是字符串：后端把 Long 序列化为字符串，避免 19 位雪花号在 JS 端被截断。
 */
export interface RefundApply {
  id: SnowflakeId
  refundNo: string
  bizType: string
  bizNo: string
  amount: number
  principalAmt: number
  giftAmt: number
  reason: string | null
  status: string
  auditor: string | null
  auditRemark: string | null
  createdAt: string
  finishedAt: string | null
}

/** 与后端 RefundApply.BIZ_* 常量一致 */
export const REFUND_BIZ_TEXT: Record<string, string> = {
  CARD: '次卡退款',
  GOODS: '商品订单退款',
  APPOINT: '预约退款',
  SETTLE: '结算多收退款',
  MANUAL: '门店人工退款'
}

/** 当前仅次卡开放线上申请，其余来源后端会显式拒绝 */
export const REFUND_BIZ_CARD = 'CARD'

export const myRefunds = () => get<RefundApply[]>('/api/v1/refunds')

/**
 * 发起退款申请：只提交"退哪张单"，金额由服务端按业务实付计算，前端无权指定。
 */
export const applyCardRefund = (cardNo: string, reason?: string) =>
  post<RefundApply>('/api/v1/refunds', { bizType: REFUND_BIZ_CARD, bizNo: cardNo, reason })

export const cancelRefund = (id: SnowflakeId) => post<RefundApply>(`/api/v1/refunds/${id}/cancel`)
