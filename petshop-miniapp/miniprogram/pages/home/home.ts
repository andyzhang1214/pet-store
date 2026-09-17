import { themeBehavior } from '../../behaviors/theme'
import { currentTheme, themeGreeting } from '../../themes'

Page({
  behaviors: [themeBehavior],
  data: {
    greeting: '',
    quickEntries: [
      { key: 'mall', icon: '🛍', title: '逛商城', desc: '宠物好物', url: '/pages/mall/mall', tab: true },
      { key: 'pets', icon: '🐱', title: '我的宠物', desc: '档案管理', url: '/subpages/pets/pets', tab: false },
      { key: 'wallet', icon: '💰', title: '余额钱包', desc: '充值流水', url: '/subpages/wallet/wallet', tab: false },
      { key: 'health', icon: '🛡', title: '健康档案', desc: '疫苗驱虫', url: '/subpages/health/health', tab: false }
    ]
  },
  onShow() {
    this.setData({ greeting: themeGreeting(currentTheme()) })
  },
  goBooking() {
    wx.switchTab({ url: '/pages/booking/booking' })
  },
  goEntry(e: WechatMiniprogram.TouchEvent) {
    const { url, tab } = e.currentTarget.dataset as { url: string; tab: boolean }
    if (tab) {
      wx.switchTab({ url })
    } else {
      wx.navigateTo({ url })
    }
  }
})
