/** 金额：后端金额单位即为「元」，这里只做两位小数格式化（历史注释误写为"分转显示"，已更正） */
export function money(n: number | string | null | undefined): string {
  const v = typeof n === 'string' ? parseFloat(n) : n || 0
  return v.toFixed(2)
}

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** '2026-09-16' → '9月16日 周三' */
export function formatDate(date: string): string {
  const d = new Date(date)
  return `${d.getMonth() + 1}月${d.getDate()}日 ${WEEK[d.getDay()]}`
}

export function dateOffsetLabel(date: string): string {
  const d = new Date(date)
  const today = new Date()
  const diff = Math.round((d.getTime() - new Date(today.toDateString()).getTime()) / 86400000)
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  if (diff === 2) return '后天'
  return formatDate(date)
}

export const APPT_STATUS: Record<string, { text: string; cls: string }> = {
  PENDING: { text: '待到店', cls: 'warn' },
  ARRIVED: { text: '已到店', cls: 'info' },
  SETTLING: { text: '结算中', cls: 'info' },
  SETTLED: { text: '已完成', cls: 'ok' },
  CANCELLED: { text: '已取消', cls: 'muted' },
  NO_SHOW: { text: '已爽约', cls: 'danger' }
}

/** 与服务端 ServiceCategory 枚举严格一致：WASH / GROOM / SPA / BOARD / VACCINE / WORM（注意是 WORM 不是 DEWORM） */
export const SERVICE_CATEGORY: Record<string, { text: string; icon: string }> = {
  WASH: { text: '洗护', icon: '🛁' },
  GROOM: { text: '美容', icon: '✂️' },
  SPA: { text: '药浴SPA', icon: '🫧' },
  BOARD: { text: '寄养', icon: '🏠' },
  VACCINE: { text: '疫苗', icon: '🛡' },
  WORM: { text: '驱虫', icon: '🐛' }
}

/** 与健康档案时序一致：服务端 HealthLogType = VACCINE / WORM / WEIGHT / BATH / GROOM */
export const HEALTH_LOG: Record<string, { text: string; icon: string }> = {
  VACCINE: { text: '疫苗', icon: '🛡' },
  WORM: { text: '驱虫', icon: '🐛' },
  WEIGHT: { text: '体重', icon: '⚖️' },
  BATH: { text: '洗澡', icon: '🛁' },
  GROOM: { text: '美容', icon: '✂️' }
}

/** 与服务端 RefundApply 状态严格一致：APPLYING / REFUNDED / REJECTED / CANCELLED */
export const REFUND_STATUS: Record<string, { text: string; cls: string }> = {
  APPLYING: { text: '审核中', cls: 'warn' },
  REFUNDED: { text: '已退入余额', cls: 'ok' },
  REJECTED: { text: '已驳回', cls: 'danger' },
  CANCELLED: { text: '已撤回', cls: 'muted' }
}

export function guaranteeText(type: string | null): string {
  if (type === 'BALANCE') return '余额冻结担保'
  if (type === 'CARD') return '次卡预扣担保'
  return '免担保'
}
