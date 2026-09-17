import { themeBehavior } from '../../behaviors/theme'
import { listGoods, GoodsVO } from '../../api/mall'
import { getWithTtl, refreshTtl } from '../../utils/cache'
import { money } from '../../utils/format'
import { showApiError } from '../../utils/request'

const CACHE_KEY = 'mall-goods'

interface GoodsRow extends GoodsVO {
  priceText: string
  skuCount: number
  soldOut: boolean
}

/**
 * 商城列表。
 *
 * 分类栏不是前端自拟的固定键（曾经那套 FOOD/SNACK/TOY/SALE 与库里的中文分类完全对不上，
 * 一次都筛不出数据 —— 审查 M-9），而是从服务端返回的 category 字段归并出来的。
 * 好处是运营加一个新分类，这里自动出现，不必前后端同步改代码。
 *
 * 走 TTL 5 分钟缓存（技术设计 4.x 档 2）：商品列表低频变化，下拉刷新强制穿透。
 * 分类筛选在前端内存里做：列表接口一次返回全量上架商品，再切分类不必重新请求。
 */
Page({
  behaviors: [themeBehavior],
  data: {
    categories: ['全部'] as string[],
    activeCategory: '全部',
    /** 服务端全量上架商品 */
    all: [] as GoodsRow[],
    /** 当前分类下展示的商品 */
    goods: [] as GoodsRow[],
    loading: true,
    failed: false
  },
  onShow() {
    this.load()
  },
  async load(force = false) {
    this.setData({ loading: true, failed: false })
    try {
      const raw = force
        ? await refreshTtl(CACHE_KEY, listGoods)
        : await getWithTtl(CACHE_KEY, listGoods)
      const all: GoodsRow[] = raw.map((g) => {
        const stocks = (g.skus || []).map((s) => s.stock || 0)
        return {
          ...g,
          priceText: money(g.priceFrom),
          skuCount: (g.skus || []).length,
          // 全部规格都没货才算售罄；只要有一个规格有货就能下单
          soldOut: stocks.length > 0 && stocks.every((n) => n <= 0)
        }
      })
      // 归并分类：保持服务端返回顺序，不重排 —— 运营调顺序前端跟着走
      const categories = ['全部']
      raw.forEach((g) => {
        if (g.category && categories.indexOf(g.category) < 0) {
          categories.push(g.category)
        }
      })
      // 选中的分类若已消失（商品全下架）则回落「全部」，否则会停在空列表上
      const activeCategory = categories.indexOf(this.data.activeCategory) >= 0
        ? this.data.activeCategory
        : '全部'
      this.setData({ all, categories, activeCategory, loading: false })
      this.applyFilter()
    } catch (e) {
      this.setData({ loading: false, failed: true })
      showApiError(e)
    }
  },
  applyFilter() {
    const { all, activeCategory } = this.data
    this.setData({
      goods: activeCategory === '全部' ? all : all.filter((g) => g.category === activeCategory)
    })
  },
  onPullDownRefresh() {
    this.load(true).then(() => wx.stopPullDownRefresh())
  },
  pickCategory(e: WechatMiniprogram.TouchEvent) {
    this.setData({ activeCategory: e.currentTarget.dataset.key as string })
    this.applyFilter()
  },
  openGoods(e: WechatMiniprogram.TouchEvent) {
    wx.navigateTo({ url: `/subpages/goods/goods?id=${e.currentTarget.dataset.id}` })
  },
  goOrders() {
    wx.navigateTo({ url: '/subpages/orders/orders' })
  }
})
