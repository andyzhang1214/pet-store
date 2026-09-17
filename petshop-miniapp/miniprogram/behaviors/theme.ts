import { currentTheme, ThemeId } from '../themes'

/** 主题 Behavior：页面根节点挂 class="page theme-{{theme}}" 即可整体换肤 */
export const themeBehavior = Behavior({
  data: {
    theme: 'standard' as ThemeId
  },
  lifetimes: {
    attached() {
      this.setData({ theme: currentTheme() })
    }
  },
  pageLifetimes: {
    show() {
      const t = currentTheme()
      if (this.data.theme !== t) {
        this.setData({ theme: t })
      }
    }
  }
})
