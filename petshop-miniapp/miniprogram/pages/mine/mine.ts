import { themeBehavior } from '../../behaviors/theme'
import { THEMES, currentTheme, setTheme, ThemeId } from '../../themes'
import { getSession, ensureSession, showApiError } from '../../utils/request'
import { wallet, WalletVO } from '../../api/wallet'

const LEVEL_NAMES: Record<number, string> = { 1: '普通会员', 2: '银爪会员', 3: '金爪会员' }

Page({
  behaviors: [themeBehavior],
  data: {
    nickname: '',
    levelName: '',
    walletInfo: null as WalletVO | null,
    themes: THEMES,
    activeTheme: 'standard' as ThemeId,
    entries: [
      { key: 'orders', icon: '🛍', title: '我的订单', desc: '自提码 · 退款申请', url: '/subpages/orders/orders' },
      { key: 'appointments', icon: '📅', title: '我的预约', desc: '签到核销 · 取消', url: '/subpages/appointments/appointments' },
      { key: 'pets', icon: '🐱', title: '我的宠物', desc: '宠物档案管理', url: '/subpages/pets/pets' },
      { key: 'health', icon: '🛡', title: '健康档案', desc: '疫苗 · 驱虫时间轴', url: '/subpages/health/health' },
      { key: 'wallet', icon: '💰', title: '余额与流水', desc: '充值 · 账单明细', url: '/subpages/wallet/wallet' }
    ],
    store: {
      name: '爪爪星球宠物生活馆',
      businessHours: '09:00 - 19:00',
      address: '示例市喵汪区爪爪路 88 号',
      phone: '0755-88886666'
    }
  },

  async onShow() {
    this.setData({ activeTheme: currentTheme() })
    await this.loadMember()
    this.loadWallet()
  },

  async loadMember() {
    let session = getSession()
    if (!session) {
      try {
        await ensureSession()
        session = getSession()
      } catch (e) {
        showApiError(e)
        return
      }
    }
    if (session) {
      this.setData({
        nickname: session.nickname || '铲屎官',
        levelName: LEVEL_NAMES[session.level] || '普通会员'
      })
    }
  },

  /** 资金事实：不缓存，进页实时拉取 */
  async loadWallet() {
    try {
      const w = await wallet()
      this.setData({ walletInfo: w })
    } catch (e) {
      // 未登录或网络异常时静默，避免打断浏览
      console.warn('[mine] 钱包拉取失败', e)
    }
  },

  goEntry(e: WechatMiniprogram.TouchEvent) {
    wx.navigateTo({ url: e.currentTarget.dataset.url as string })
  },

  pickTheme(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id as ThemeId
    setTheme(id)
    this.setData({ activeTheme: id, theme: id })
    wx.showToast({ title: `已切换：${THEMES.find((t) => t.id === id)?.name}`, icon: 'none' })
  },

  callStore() {
    wx.makePhoneCall({ phoneNumber: this.data.store.phone })
  }
})
