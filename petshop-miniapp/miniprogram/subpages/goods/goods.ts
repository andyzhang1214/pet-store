import { themeBehavior } from '../../behaviors/theme'
import { goodsDetail, createOrder, GoodsVO, SkuVO } from '../../api/mall'
import { money } from '../../utils/format'
import { showApiError } from '../../utils/request'
import type { SnowflakeId } from '../../utils/request'

interface SkuRow extends SkuVO {
  priceText: string
  soldOut: boolean
}

/**
 * 商品详情与下单。
 *
 * 下单只提交 {@code skuId + qty}：金额、库存扣减、核销码全由服务端决定。
 * 客户端算出来的价格一概不信 —— 与退款「金额不由客户端决定」是同一条口径。
 *
 * 库存是「下单即锁」而不是「付款才扣」，所以下单后放弃支付会占着库存，
 * 需要用户主动取消（取消会立刻回补）或由后续超时任务回收。
 */
Page({
  behaviors: [themeBehavior],
  goodsId: '' as SnowflakeId | '',

  data: {
    loading: true,
    failed: false,
    goods: null as GoodsVO | null,
    skus: [] as SkuRow[],
    activeSkuId: '' as SnowflakeId | '',
    activeSku: null as SkuRow | null,
    qty: 1,
    maxQty: 1,
    submitting: false
  },

  onLoad(query: Record<string, string | undefined>) {
    this.goodsId = query.id || ''
    this.load()
  },

  async load() {
    if (!this.goodsId) {
      this.setData({ loading: false, failed: true })
      return
    }
    try {
      const g = await goodsDetail(this.goodsId)
      const skus: SkuRow[] = (g.skus || []).map((s) => ({
        ...s,
        priceText: money(s.price),
        soldOut: (s.stock || 0) <= 0
      }))
      // 默认选第一个有货的规格；全都没货时停在第一个，由 UI 显示售罄并禁用下单
      const first = skus.filter((s) => !s.soldOut)[0] || skus[0] || null
      this.setData({ goods: g, skus, loading: false })
      this.selectSku(first ? first.id : '')
    } catch (e) {
      this.setData({ loading: false, failed: true })
      showApiError(e)
    }
  },

  selectSku(id: SnowflakeId | '') {
    const sku = this.data.skus.filter((s) => s.id === id)[0] || null
    // 上限就是当前库存：真实的库存裁决在服务端（下单时用 CAS 扣减），
    // 前端这个上限只是为了让用户少撞一次「库存不足」，不额外发明业务规则。
    const maxQty = sku ? Math.max(1, sku.stock || 1) : 1
    this.setData({
      activeSkuId: id,
      activeSku: sku,
      maxQty,
      // 切到库存更少的规格时数量要跟着收敛，否则会把超量的 qty 提交上去，
      // 结果是下单被库存 CAS 拒绝、用户只看到「库存不足」而不知道是自己没改数量
      qty: Math.min(this.data.qty, maxQty)
    })
  },

  pickSku(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id as SnowflakeId
    const sku = this.data.skus.filter((s) => s.id === id)[0]
    if (sku && sku.soldOut) return
    this.selectSku(id)
  },

  changeQty(e: WechatMiniprogram.TouchEvent) {
    const delta = Number(e.currentTarget.dataset.delta)
    const next = this.data.qty + delta
    if (next < 1) return
    if (next > this.data.maxQty) {
      wx.showToast({ title: `最多可买 ${this.data.maxQty} 件`, icon: 'none' })
      return
    }
    this.setData({ qty: next })
  },

  async submit() {
    const { activeSku, qty, submitting } = this.data
    if (submitting) return
    if (!activeSku) {
      wx.showToast({ title: '请选择规格', icon: 'none' })
      return
    }
    if (activeSku.soldOut) {
      wx.showToast({ title: '该规格已售罄', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      const order = await createOrder([{ skuId: activeSku.id, qty }])
      // 下单成功直接进订单页并自动发起余额支付：余额支付是即时到账语义，没有"支付中"态，
      // 所以不必留在详情页做二次确认。
      wx.redirectTo({ url: `/subpages/orders/orders?focus=${order.orderNo}&pay=1` })
    } catch (e) {
      showApiError(e)
    } finally {
      this.setData({ submitting: false })
    }
  },

  goOrders() {
    wx.navigateTo({ url: '/subpages/orders/orders' })
  }
})
