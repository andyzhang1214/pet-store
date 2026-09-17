import { get, post } from '../utils/request'
import type { SnowflakeId } from '../utils/request'

/**
 * 商城商品。字段与后端 GoodsVO 严格一致。
 * priceFrom 只用于列表页「¥xx 起」；下单金额一律由服务端按 SKU 现价重算，
 * 前端传价格字段也没用 —— 与退款「金额不由客户端决定」是同一条原则。
 */
export interface GoodsVO {
  id: SnowflakeId
  name: string
  category: string
  images: string[]
  description: string
  priceFrom: number
  skus: SkuVO[]
}

export interface SkuVO {
  id: SnowflakeId
  name: string
  price: number
  stock: number
}

/**
 * 商品订单。字段与后端 OrderVO 严格一致。
 * refundable 由服务端判定（已支付且未核销），前端只渲染，不自己算。
 */
export interface OrderVO {
  id: SnowflakeId
  orderNo: string
  total: number
  principalPaid: number
  giftPaid: number
  payType: string
  status: string
  /** 自提核销码；已核销/已退款后后端会置空，前端不必判断状态再取 */
  verifyCode: string | null
  createdAt: string
  updatedAt: string
  items: OrderItemVO[]
  refundable: boolean
}

export interface OrderItemVO {
  skuId: SnowflakeId
  name: string
  qty: number
  price: number
}

export interface CreateOrderItem {
  skuId: SnowflakeId
  qty: number
}

/** 上架商品列表 */
export const listGoods = () => get<GoodsVO[]>('/api/v1/mall/goods')

export const goodsDetail = (id: SnowflakeId) => get<GoodsVO>(`/api/v1/mall/goods/${id}`)

/** 下单：只提交 skuId + qty，金额由服务端算 */
export const createOrder = (items: CreateOrderItem[]) =>
  post<OrderVO>('/api/v1/mall/orders', { items })

export const payOrder = (orderNo: string) =>
  post<OrderVO>(`/api/v1/mall/orders/${orderNo}/pay`, { payType: 'BALANCE' })

export const cancelOrder = (orderNo: string) =>
  post<OrderVO>(`/api/v1/mall/orders/${orderNo}/cancel`)

export const myOrders = () => get<OrderVO[]>('/api/v1/mall/orders')

export const orderDetail = (orderNo: string) => get<OrderVO>(`/api/v1/mall/orders/${orderNo}`)

// ==================== 状态字典 ====================
// 后端返回原始枚举值，中文文案在前端映射（与 CARD_STATUS / REFUND_STATUS 同一处理方式）

/** 与后端 GoodsOrder.STATUS_* 严格一致 */
export const ORDER_STATUS: Record<string, { text: string; cls: string }> = {
  CREATED: { text: '待支付', cls: 'warn' },
  PAID: { text: '待自提', cls: 'info' },
  VERIFIED: { text: '已自提', cls: 'ok' },
  CANCELLED: { text: '已取消', cls: 'muted' },
  REFUNDED: { text: '已退款', cls: 'muted' }
}

/** 与后端 GoodsOrder.PAY_* 严格一致 */
export const ORDER_PAY_TEXT: Record<string, string> = {
  BALANCE: '余额支付',
  WXPAY: '微信支付'
}

/** 只提交来源单据，金额由服务端按订单实付重算 */
export const refundGoodsOrder = (orderNo: string, reason?: string) =>
  post<{ id: SnowflakeId; refundNo: string; status: string; amount: number }>('/api/v1/refunds', {
    bizType: 'GOODS',
    bizNo: orderNo,
    reason
  })
