import { themeBehavior } from '../../behaviors/theme'
import {
  myOrders, payOrder, cancelOrder, refundGoodsOrder,
  OrderVO, ORDER_STATUS, ORDER_PAY_TEXT
} from '../../api/mall'
import { money } from '../../utils/format'
import { showApiError } from '../../utils/request'
import type { SnowflakeId } from '../../utils/request'

interface ItemRow {
  /** 用来当 wx:key —— 同一订单出现两个同名商品时 name 会重复（对应审查 P3-9） */
  skuId: SnowflakeId
  name: string
  qty: number
  priceText: string
}

interface OrderRow extends OrderVO {
  statusText: string
  statusCls: string
  payText: string
  totalText: string
  createdText: string
  itemRows: ItemRow[]
  /** 待支付 */
  canPay: boolean
  /** 待支付可取消（取消即时回补库存） */
  canCancel: boolean
  /** 申请退款入口 = 服务端 refundable，前端不自己按状态猜 */
  canRefund: boolean
}

const TABS = [
  { key: '', name: '全部' },
  { key: 'CREATED', name: '待支付' },
  { key: 'PAID', name: '待自提' },
  { key: 'VERIFIED', name: '已自提' },
  { key: 'REFUNDED', name: '已退款' }
]

/**
 * 我的订单。
 *
 * 「能不能退款」只认服务端返回的 refundable，不在前端按状态自行推断 ——
 * 判定规则（已支付 && 未核销）一旦变化，前端不需要跟着改，也不会出现前后端各说各话。
 *
 * 退款只入账户余额，不支持原路退回微信；这点在申请前的弹窗里要说清楚，
 * 否则用户会以为钱会回到微信零钱。
 */
Page({
  behaviors: [themeBehavior],

  data: {
    tabs: TABS,
    activeTab: '',
    all: [] as OrderRow[],
    orders: [] as OrderRow[],
    loading: true,
    failed: false,
    /**
     * 正在处理中的订单号：await 期间忽略同一单的重复点击。
     * 服务端状态机仍会兜底（CAS 抢不到就报错），但用户不该看到两次"支付成功"（对应审查 P3-10）。
     */
    busyNo: '' as string,
    /** 从商品详情下单后带过来的订单号，用于自动发起支付 */
    focusOrderNo: '' as string
  },

  onLoad(query: Record<string, string | undefined>) {
    this.setData({ focusOrderNo: query.focus || '' })
    this.autoPay = query.pay === '1'
  },
  autoPay: false,

  onShow() {
    this.load()
  },

  async load() {
    this.setData({ loading: true, failed: false })
    try {
      const raw = await myOrders()
      const all: OrderRow[] = raw.map((o) => this.toRow(o))
      this.setData({ all, loading: false })
      this.applyFilter()
      if (this.autoPay && this.data.focusOrderNo) {
        this.autoPay = false
        const target = all.filter((o) => o.orderNo === this.data.focusOrderNo)[0]
        if (target && target.canPay) {
          await this.doPay(target.orderNo)
        }
      }
    } catch (e) {
      this.setData({ loading: false, failed: true })
      showApiError(e)
    }
  },

  toRow(o: OrderVO): OrderRow {
    const meta = ORDER_STATUS[o.status] || { text: o.status, cls: 'muted' }
    return {
      ...o,
      statusText: meta.text,
      statusCls: meta.cls,
      payText: ORDER_PAY_TEXT[o.payType] || o.payType,
      totalText: money(o.total),
      createdText: (o.createdAt || '').slice(5, 16),
      itemRows: (o.items || []).map((it) => ({
        skuId: it.skuId,
        name: it.name,
        qty: it.qty,
        priceText: money(it.price)
      })),
      canPay: o.status === 'CREATED',
      canCancel: o.status === 'CREATED',
      canRefund: !!o.refundable
    }
  },

  applyFilter() {
    const { all, activeTab } = this.data
    this.setData({
      orders: activeTab ? all.filter((o) => o.status === activeTab) : all
    })
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  pickTab(e: WechatMiniprogram.TouchEvent) {
    this.setData({ activeTab: e.currentTarget.dataset.key as string })
    this.applyFilter()
  },

  async doPay(orderNo: string) {
    if (this.data.busyNo) return
    // 标志在弹窗之前就置上：连点两下时第二次不会再弹出确认框，
    // 否则用户会连确认两次、发出两笔支付请求。
    this.setData({ busyNo: orderNo })
    try {
      const { confirm } = await wx.showModal({
        title: '余额支付',
        content: '将从账户余额扣款（优先扣赠送金）。确认支付？',
        confirmText: '确认支付'
      })
      if (!confirm) return
      await payOrder(orderNo)
      wx.showToast({ title: '支付成功，请到店自提', icon: 'none' })
      this.load()
    } catch (e) {
      showApiError(e)
    } finally {
      this.setData({ busyNo: '' })
    }
  },

  async doCancel(orderNo: string) {
    if (this.data.busyNo) return
    this.setData({ busyNo: orderNo })
    try {
      const { confirm } = await wx.showModal({
        title: '取消订单',
        content: '取消后库存会立刻回补，订单不可恢复。确认取消？'
      })
      if (!confirm) return
      await cancelOrder(orderNo)
      wx.showToast({ title: '已取消', icon: 'none' })
      this.load()
    } catch (e) {
      showApiError(e)
    } finally {
      this.setData({ busyNo: '' })
    }
  },

  async doRefund(orderNo: string) {
    if (this.data.busyNo) return
    this.setData({ busyNo: orderNo })
    try {
      const { confirm } = await wx.showModal({
        title: '申请退款',
        content: '退款金额由门店按订单实付核定，审核通过后退入账户余额（不支持提现到微信）。提交后需等门店审核。',
        confirmText: '提交申请'
      })
      if (!confirm) return
      await refundGoodsOrder(orderNo, '不想要了，申请退款')
      wx.showToast({ title: '已提交，待门店审核', icon: 'none' })
      this.load()
    } catch (e) {
      showApiError(e)
    } finally {
      this.setData({ busyNo: '' })
    }
  },

  onTapPay(e: WechatMiniprogram.TouchEvent) {
    this.doPay(e.currentTarget.dataset.no as string)
  },
  onTapCancel(e: WechatMiniprogram.TouchEvent) {
    this.doCancel(e.currentTarget.dataset.no as string)
  },
  onTapRefund(e: WechatMiniprogram.TouchEvent) {
    this.doRefund(e.currentTarget.dataset.no as string)
  },
  copyCode(e: WechatMiniprogram.TouchEvent) {
    wx.setClipboardData({ data: e.currentTarget.dataset.code as string })
  },
  goMall() {
    wx.switchTab({ url: '/pages/mall/mall' })
  }
})
