import { themeBehavior } from '../../behaviors/theme'
import { wallet, ledger, myCards, WalletVO, BalanceLedger, LedgerPage, MemberCardVO } from '../../api/wallet'
import { myRefunds, applyCardRefund, cancelRefund, RefundApply, REFUND_BIZ_TEXT } from '../../api/refund'
import { REFUND_STATUS } from '../../utils/format'
import { showApiError } from '../../utils/request'
import type { SnowflakeId } from '../../utils/request'

const BIZ_TEXT: Record<string, string> = {
  RECHARGE: '充值',
  FREEZE: '预约冻结',
  UNFREEZE: '解除冻结',
  CONSUME: '消费扣减',
  REFUND: '余额出账',
  REFUND_IN: '退款入账',
  ADJUST: '门店调整'
}

const CARD_STATUS: Record<string, { text: string; cls: string }> = {
  USING: { text: '使用中', cls: 'ok' },
  EXHAUSTED: { text: '已用尽', cls: 'muted' },
  EXPIRED: { text: '已过期', cls: 'muted' },
  REFUNDED: { text: '已退款', cls: 'info' }
}

type CardRow = MemberCardVO & { statusText: string; statusCls: string; timesText: string }

type RefundRow = RefundApply & {
  statusText: string
  statusCls: string
  bizText: string
  amtText: string
  timeText: string
}

Page({
  behaviors: [themeBehavior],
  data: {
    walletInfo: null as WalletVO | null,
    records: [] as Array<BalanceLedger & { bizText: string; amtText: string; sign: string }>,
    page: 1,
    total: 0,
    loading: false,
    cards: [] as CardRow[],
    refunds: [] as RefundRow[],
    /**
     * 正在处理中的次卡/退款单 id：await 期间忽略重复点击。
     * 退款是资金动作，双击会让服务端收到两次申请（服务端有 CAS 兜底，但用户会看到两条记录）。
     */
    busyId: '' as SnowflakeId
  },
  onShow() {
    this.setData({ records: [], page: 1, total: 0 })
    /** 资金事实：进页实时拉取，不缓存 */
    this.loadWallet()
    this.loadLedger()
    this.loadCards()
    this.loadRefunds()
  },
  async loadWallet() {
    try {
      this.setData({ walletInfo: await wallet() })
    } catch (e) {
      showApiError(e)
    }
  },
  async loadLedger() {
    if (this.data.loading) return
    this.setData({ loading: true })
    try {
      const res: LedgerPage = await ledger(this.data.page, 20)
      const mapped = res.records.map((r) => {
        // 后端流水金额本身带符号（消费为负），这里统一取绝对值 + 由数值符号决定正负，
        // 避免出现 "-¥-120.00" 双重负号（对应审查 M-3）。
        const raw = Number(r.principalAmt || 0) + Number(r.giftAmt || 0)
        const frozenOp = r.bizType === 'FREEZE' || r.bizType === 'UNFREEZE'
        return {
          ...r,
          bizText: BIZ_TEXT[r.bizType] || r.bizType,
          amtText: Math.abs(raw).toFixed(2),
          sign: raw < 0 ? '-' : '+',
          frozenOp,
          // FREEZE / UNFREEZE 金额恒为 0，展示冻结总额更有信息量
          frozenText: Number(r.frozenAfter || 0).toFixed(2)
        }
      })
      this.setData({
        records: this.data.records.concat(mapped),
        // total 后端走 Long→字符串（雪花 id 同一套序列化规则），这里显式转数字供翻页比较
        total: Number(res.total || 0),
        page: this.data.page + 1
      })
    } catch (e) {
      showApiError(e)
    } finally {
      this.setData({ loading: false })
    }
  },
  async loadCards() {
    try {
      const cards = await myCards()
      this.setData({
        cards: cards.map((c) => ({
          ...c,
          timesText: `${c.remainTimes - (c.preDeductCount || 0)} / ${c.totalTimes}`,
          statusText: (CARD_STATUS[c.status] || { text: c.status }).text,
          statusCls: (CARD_STATUS[c.status] || { cls: 'muted' }).cls
        }))
      })
    } catch (e) {
      showApiError(e)
    }
  },
  async loadRefunds() {
    try {
      const list = await myRefunds()
      this.setData({
        refunds: list.map((r) => ({
          ...r,
          statusText: (REFUND_STATUS[r.status] || { text: r.status }).text,
          statusCls: (REFUND_STATUS[r.status] || { cls: 'muted' }).cls,
          bizText: REFUND_BIZ_TEXT[r.bizType] || r.bizType,
          amtText: Number(r.amount || 0).toFixed(2),
          timeText: (r.createdAt || '').replace('T', ' ').slice(0, 16)
        }))
      })
    } catch (e) {
      showApiError(e)
    }
  },
  /** 次卡整卡退款：只提交卡号，金额由服务端按购买实付计算 */
  async applyRefund(e: WechatMiniprogram.BaseEvent) {
    // id 保持字符串：19 位雪花号用 Number() 会被截断
    const id = e.currentTarget.dataset.id as SnowflakeId
    const card = this.data.cards.find((c) => c.id === id)
    if (!card || !card.refundable) return
    if (this.data.busyId) return
    // 标志先置上再弹窗：连点两下时第二次不会再弹出确认框
    this.setData({ busyId: id })
    try {
      const { confirm } = await wx.showModal({
        title: '申请退款',
        content: `「${card.name}」将按购买实付 ¥${Number(card.paidAmount || 0).toFixed(2)} 整卡退回账户余额，待门店审核通过后到账。退款只进余额，不支持提现到微信。`
      })
      if (!confirm) return
      await applyCardRefund(card.cardNo, '整卡未使用，申请退款')
      wx.showToast({ title: '已提交，待门店审核', icon: 'none' })
      this.loadCards()
      this.loadRefunds()
    } catch (err) {
      showApiError(err)
    } finally {
      this.setData({ busyId: '' })
    }
  },
  async cancelRefund(e: WechatMiniprogram.BaseEvent) {
    const id = e.currentTarget.dataset.id as SnowflakeId
    if (this.data.busyId) return
    this.setData({ busyId: id })
    try {
      const { confirm } = await wx.showModal({ title: '撤回申请', content: '撤回后可重新申请，确认撤回？' })
      if (!confirm) return
      await cancelRefund(id)
      wx.showToast({ title: '已撤回' })
      this.loadRefunds()
    } catch (err) {
      showApiError(err)
    } finally {
      this.setData({ busyId: '' })
    }
  },
  onReachBottom() {
    if (this.data.records.length < this.data.total) {
      this.loadLedger()
    }
  },
  goRecharge() {
    // 充值流程（微信支付 + 充值档位）在支付链路迭代中接入
    wx.showToast({ title: '充值功能即将开放', icon: 'none' })
  }
})
