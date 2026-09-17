import { config, TOKEN_KEY, MEMBER_KEY } from '../config'

export interface ApiError {
  code: number
  msg: string
}

/**
 * 雪花主键。
 *
 * 后端把 Long 序列化成 JSON 字符串 —— 因为雪花 id 是 19 位十进制数，
 * 超过 JS `Number` 的安全整数上限（2^53-1，16 位），一旦当数字解析就会被静默截断：
 * `2099772780119552001` 变成 `2099772780119552000`，请求随即打到不存在的单据上，
 * 而前端只会看到「资源不存在」这类看起来像后端 bug 的报错。
 *
 * 因此所有主键在客户端一律当字符串：不要 `Number()`、不要 `parseInt`、不要和数字比较。
 * 需要拼接进 URL 时直接模板串插值即可。
 */
export type SnowflakeId = string

interface ApiResp<T> {
  code: number
  msg: string
  data: T
}

export interface MemberSession {
  memberId: SnowflakeId
  nickname: string
  level: number
}

let loginPromise: Promise<void> | null = null

export function getToken(): string {
  return (wx.getStorageSync(TOKEN_KEY) as string) || ''
}

export function getSession(): MemberSession | null {
  return (wx.getStorageSync(MEMBER_KEY) as MemberSession) || null
}

function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => (res.code ? resolve(res.code) : reject(new Error('wx.login 无 code'))),
      fail: reject
    })
  })
}

type HttpBody = string | WechatMiniprogram.IAnyObject | undefined

function http<T>(method: string, path: string, data: HttpBody = undefined, auth = false): Promise<T> {
  const header: Record<string, string> = { 'content-type': 'application/json' }
  if (auth && getToken()) {
    header.Authorization = `Bearer ${getToken()}`
  }
  return new Promise<T>((resolve, reject) => {
    const handle = (rawData: unknown, statusCode: number) => {
      const body = rawData as ApiResp<T> | undefined
      if (statusCode === 401) {
        reject({ code: 401, msg: '未登录或登录已过期' } as ApiError)
        return
      }
      if (statusCode >= 400 || !body || body.code !== 200) {
        reject({ code: body?.code ?? statusCode, msg: body?.msg ?? '网络异常' } as ApiError)
        return
      }
      resolve(body.data)
    }
    const fail = (err: unknown) => {
      // 超时和"连不上"对用户是两件事：前者重试通常能成功，后者要先看网络/后端是否在跑。
      // wx.request 的失败统一走这里，只能从 errMsg 里区分（对应审查 P3-11）。
      const msg = String((err as { errMsg?: string })?.errMsg || '')
      if (msg.includes('timeout')) {
        reject({ code: -1, msg: '请求超时，请检查网络后重试' } as ApiError)
        return
      }
      reject({ code: -1, msg: '网络连接失败' } as ApiError)
    }

    if (config.env === 'cloud') {
      wx.cloud
        .callContainer({
          config: { env: '' },
          path: `${config.cloudContainer.pathPrefix}${path}`,
          method: method as 'GET' | 'POST' | 'PUT' | 'DELETE',
          header,
          data,
          timeout: config.timeoutMs,
        })
        .then((res) => handle(res.data, res.statusCode))
        .catch(fail)
    } else {
      wx.request({
        url: `${config.devBaseUrl}${path}`,
        method: method as 'GET' | 'POST' | 'PUT' | 'DELETE',
        header,
        data,
        timeout: config.timeoutMs,
        success: (resp) => handle(resp.data, resp.statusCode),
        fail
      })
    }
  })
}

/** 静默登录：wx.login 换 token（后端 mock 模式下 openid = mock-{code}） */
export function ensureSession(force = false): Promise<void> {
  if (!force && getToken()) {
    return Promise.resolve()
  }
  if (loginPromise) {
    return loginPromise
  }
  loginPromise = wxLoginCode()
    .then((code) => http<{ token: string; memberId: SnowflakeId; nickname: string; level: number }>('POST', '/api/v1/auth/login', { code }))
    .then((data) => {
      wx.setStorageSync(TOKEN_KEY, data.token)
      wx.setStorageSync(MEMBER_KEY, { memberId: data.memberId, nickname: data.nickname, level: data.level })
    })
    .finally(() => {
      loginPromise = null
    })
  return loginPromise
}

/** 带鉴权的业务请求；401 时自动重登并重试一次 */
export async function request<T>(method: string, path: string, data: HttpBody = undefined): Promise<T> {
  await ensureSession()
  try {
    return await http<T>(method, path, data, true)
  } catch (e) {
    const err = e as ApiError
    if (err && err.code === 401) {
      await ensureSession(true)
      return http<T>(method, path, data, true)
    }
    throw e
  }
}

export const get = <T>(path: string) => request<T>('GET', path)
export const post = <T>(path: string, data: HttpBody = undefined) => request<T>('POST', path, data)
export const put = <T>(path: string, data: HttpBody = undefined) => request<T>('PUT', path, data)
export const del = <T>(path: string) => request<T>('DELETE', path)

export function showApiError(e: unknown): void {
  const err = e as ApiError
  wx.showToast({ title: err?.msg || '请求失败', icon: 'none' })
}
