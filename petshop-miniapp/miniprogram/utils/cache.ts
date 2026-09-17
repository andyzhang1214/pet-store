/**
 * 小程序端本地缓存三档策略（技术设计 4.x）：
 *  1. 版本号失效：门店信息 / 价目表等低频变化数据
 *  2. TTL 短缓存：商品列表等（默认 5 分钟，下拉刷新强制穿透）
 *  3. 资金事实不缓存：余额 / 次卡余次 / 优惠券 —— 调用方直接请求，勿走本模块
 */

interface VersionedEntry<T> {
  version: string
  data: T
}

interface TtlEntry<T> {
  savedAt: number
  data: T
}

export const TTL_5MIN = 5 * 60 * 1000

/** 档1：版本号一致时读缓存，否则穿透加载并回写 */
export async function getWithVersion<T>(key: string, version: string, loader: () => Promise<T>): Promise<T> {
  const cached = wx.getStorageSync(`v:${key}`) as VersionedEntry<T> | undefined
  if (cached && cached.version === version) {
    return cached.data
  }
  const data = await loader()
  wx.setStorageSync(`v:${key}`, { version, data } as VersionedEntry<T>)
  return data
}

/** 档2：TTL 内读缓存，过期穿透加载并回写 */
export async function getWithTtl<T>(key: string, loader: () => Promise<T>, ttlMs = TTL_5MIN): Promise<T> {
  const cached = wx.getStorageSync(`t:${key}`) as TtlEntry<T> | undefined
  if (cached && Date.now() - cached.savedAt < ttlMs) {
    return cached.data
  }
  const data = await loader()
  wx.setStorageSync(`t:${key}`, { savedAt: Date.now(), data } as TtlEntry<T>)
  return data
}

/** 强制穿透（下拉刷新用）：加载后直接回写 TTL 缓存 */
export async function refreshTtl<T>(key: string, loader: () => Promise<T>): Promise<T> {
  const data = await loader()
  wx.setStorageSync(`t:${key}`, { savedAt: Date.now(), data } as TtlEntry<T>)
  return data
}
