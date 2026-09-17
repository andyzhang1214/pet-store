import { ensureSession } from './utils/request'
import { currentTheme } from './themes'
import { config } from './config'

App({
  onLaunch() {
    if (config.env === 'cloud') {
      wx.cloud.init({ traceUser: true })
    }
    // 静默登录；失败不阻塞启动，进入页面后按需重试
    ensureSession().catch(() => {
      console.warn('[petshop] 启动静默登录失败，将在首个请求时重试')
    })
  },
  globalData: {
    theme: currentTheme()
  }
})
