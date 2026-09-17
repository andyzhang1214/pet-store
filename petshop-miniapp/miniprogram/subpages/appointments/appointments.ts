import { themeBehavior } from '../../behaviors/theme'
import { listAppointments, cancelAppointment, confirmArrival, ApptVO } from '../../api/booking'
import { APPT_STATUS, guaranteeText } from '../../utils/format'
import { showApiError } from '../../utils/request'
import type { SnowflakeId } from '../../utils/request'

interface ApptRow extends ApptVO {
  statusText: string
  statusCls: string
  guaranteeText: string
  canSelfConfirm: boolean
}

/** 本地日期 YYYY-MM-DD（自助确认仅预约当天开放，与后端校验一致） */
function todayStr(): string {
  const d = new Date()
  const p = (n: number) => (n < 10 ? '0' + n : '' + n)
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

Page({
  behaviors: [themeBehavior],
  data: {
    tabs: [
      { key: '', name: '全部' },
      { key: 'PENDING', name: '待到店' },
      { key: 'ARRIVED', name: '已到店' },
      { key: 'SETTLED', name: '已完成' }
    ],
    activeTab: '',
    rows: [] as ApptRow[],
    loading: false
  },
  onShow() {
    this.load()
  },
  async load() {
    this.setData({ loading: true })
    try {
      const list = await listAppointments(this.data.activeTab || undefined)
      const today = todayStr()
      const rows: ApptRow[] = list.map((a) => {
        const meta = APPT_STATUS[a.status] || { text: a.status, cls: 'muted' }
        return {
          ...a,
          statusText: meta.text,
          statusCls: meta.cls,
          guaranteeText: guaranteeText(a.guaranteeType),
          canSelfConfirm: a.status === 'PENDING' && a.slotDate === today
        }
      })
      this.setData({ rows })
    } catch (e) {
      showApiError(e)
    } finally {
      this.setData({ loading: false })
    }
  },
  pickTab(e: WechatMiniprogram.TouchEvent) {
    this.setData({ activeTab: e.currentTarget.dataset.key as string })
    this.load()
  },
  async cancel(e: WechatMiniprogram.TouchEvent) {
    // 预约 id 是雪花号，必须按字符串传递（Number() 会截断导致取消打到别的单/查无此单）
    const id = e.currentTarget.dataset.id as SnowflakeId
    const { confirm } = await wx.showModal({ title: '取消预约', content: '将解冻担保金额/返还次卡次数，确认取消？' })
    if (!confirm) return
    try {
      await cancelAppointment(id)
      wx.showToast({ title: '已取消' })
      this.load()
    } catch (err) {
      showApiError(err)
    }
  },
  copyCode(e: WechatMiniprogram.TouchEvent) {
    wx.setClipboardData({ data: e.currentTarget.dataset.code as string })
  },
  /** 自助确认到店：仅当天 PENDING 显示；确认后担保仍冻结，结算由门店完成 */
  async confirmToStore(e: WechatMiniprogram.TouchEvent) {
    // 预约 id 是雪花号，必须按字符串传递（Number() 会截断导致操作打到别的单）
    const id = e.currentTarget.dataset.id as SnowflakeId
    const { confirm } = await wx.showModal({ title: '确认到店', content: '确认已到店？确认后请到前台完成服务。' })
    if (!confirm) return
    try {
      await confirmArrival(id)
      wx.showToast({ title: '已确认到店' })
      this.load()
    } catch (err) {
      showApiError(err)
    }
  }
})
