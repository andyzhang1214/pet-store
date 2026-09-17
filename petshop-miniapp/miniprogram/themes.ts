import { THEME_KEY } from './config'

export type ThemeId = 'standard' | 'meow' | 'woof'

export interface ThemeMeta {
  id: ThemeId
  /** 展示名 */
  name: string
  /** 主题氛围副标题 */
  slogan: string
  /** 图标 emoji */
  icon: string
}

export const THEMES: ThemeMeta[] = [
  { id: 'standard', name: '标准模式', slogan: '专业简洁', icon: '🐾' },
  { id: 'meow', name: '喵喵主题', slogan: '猫猫加倍', icon: '🐱' },
  { id: 'woof', name: '汪汪主题', slogan: '狗狗加倍', icon: '🐶' }
]

export function currentTheme(): ThemeId {
  try {
    const v = wx.getStorageSync(THEME_KEY)
    return v === 'meow' || v === 'woof' ? v : 'standard'
  } catch {
    return 'standard'
  }
}

export function setTheme(id: ThemeId): void {
  wx.setStorageSync(THEME_KEY, id)
}

/** 主题专属问候语：喵喵/汪汪主题满配可爱元素 */
export function themeGreeting(theme: ThemeId): string {
  if (theme === 'meow') return '喵呜～今天也要闪闪发光呀'
  if (theme === 'woof') return '汪汪！快乐的一天开始啦'
  return '欢迎来到爪爪星球'
}
