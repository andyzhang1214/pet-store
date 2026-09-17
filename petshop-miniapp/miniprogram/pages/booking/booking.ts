import { themeBehavior } from '../../behaviors/theme'
import { listServices, quote, listSlots, createAppointment, ServiceItem, MatchedQuote, SlotVO, ApptVO, UsableCard } from '../../api/booking'
import { listPets, createPet, Pet } from '../../api/member'
import { wallet, WalletVO } from '../../api/wallet'
import { getWithTtl, refreshTtl } from '../../utils/cache'
import { dateOffsetLabel, SERVICE_CATEGORY } from '../../utils/format'
import { showApiError } from '../../utils/request'
import type { SnowflakeId } from '../../utils/request'

interface DateCell {
  value: string
  label: string
  weekday: string
}

Page({
  behaviors: [themeBehavior],
  data: {
    step: 1,
    steps: ['选服务', '选宠物', '选时段', '确认预约'],
    /* 门店信息（技术设计：门店信息展示在预约页） */
    store: {
      name: '爪爪星球宠物生活馆',
      businessHours: '09:00 - 19:00',
      address: '示例市喵汪区爪爪路 88 号',
      phone: '0755-88886666'
    },
    services: [] as ServiceItem[],
    selectedService: null as ServiceItem | null,
    pets: [] as Pet[],
    selectedPet: null as Pet | null,
    quoteData: null as MatchedQuote | null,
    showPetForm: false,
    petForm: { name: '', type: 'CAT', hair: 'SHORT', weight: '' },
    dates: [] as DateCell[],
    selectedDate: '',
    slots: [] as SlotVO[],
    selectedSlot: '',
    walletInfo: null as WalletVO | null,
    guarantee: 'BALANCE',
    usableCards: [] as UsableCard[],
    selectedCardId: null as SnowflakeId | null,
    remark: '',
    submitting: false,
    result: null as ApptVO | null
  },

  onLoad() {
    this.initDates()
    this.loadServices()
  },

  onShow() {
    if (this.data.step === 2 || (this.data.step === 4 && !this.data.selectedPet)) {
      this.loadPets()
    }
  },

  onPullDownRefresh() {
    this.loadServices(true).finally(() => wx.stopPullDownRefresh())
  },

  /**
   * 可选日期：今天起 14 天。
   * 后端只拒绝「早于今天」的日期（AppointmentService.create 的 date.isBefore(LocalDate.now())），
   * 今天内已过的时段由 SlotService 标记 past 并在 UI 上禁用，因此这里从 i = 0 开始，
   * 与后端可约范围保持一致（对应审查 NIT）。
   */
  initDates() {
    const cells: DateCell[] = []
    for (let i = 0; i <= 13; i++) {
      const d = new Date()
      d.setDate(d.getDate() + i)
      const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
      cells.push({ value, label: dateOffsetLabel(value), weekday: `周${'日一二三四五六'[d.getDay()]}` })
    }
    this.setData({ dates: cells, selectedDate: cells[0].value })
  },

  /** 价目/服务列表：本地缓存 TTL 档（低频变化数据，下拉刷新强制穿透） */
  async loadServices(force = false) {
    try {
      const loader = () => listServices()
      const list = force ? await refreshTtl('services', loader) : await getWithTtl('services', loader)
      const enriched = list.map((s) => ({
        ...s,
        icon: SERVICE_CATEGORY[s.category]?.icon || '🐾',
        categoryText: SERVICE_CATEGORY[s.category]?.text || s.category
      }))
      this.setData({ services: enriched })
    } catch (e) {
      showApiError(e)
    }
  },

  async loadPets() {
    try {
      const pets = await listPets()
      this.setData({ pets })
    } catch (e) {
      showApiError(e)
    }
  },

  /* ===== 步骤 1：选服务 ===== */
  pickService(e: WechatMiniprogram.TouchEvent) {
    // dataset 取出来本来就是字符串，与后端雪花 id 的字符串契约一致，不要再 cast 成 number
    const id = e.currentTarget.dataset.id as SnowflakeId
    const svc = this.data.services.find((s) => s.id === id) || null
    this.setData({ selectedService: svc, step: 2 })
    this.loadPets()
  },

  /* ===== 步骤 2：选宠物（选中即拉报价） ===== */
  async pickPet(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id as SnowflakeId
    const pet = this.data.pets.find((p) => p.id === id) || null
    if (!pet || !this.data.selectedService) return
    wx.showLoading({ title: '获取报价' })
    try {
      const q = await quote(this.data.selectedService.id, pet.id)
      this.setData({ selectedPet: pet, quoteData: q, step: 3 })
      this.loadSlots()
    } catch (err) {
      showApiError(err)
    } finally {
      wx.hideLoading()
    }
  },

  togglePetForm() {
    this.setData({ showPetForm: !this.data.showPetForm })
  },

  onPetFormInput(e: WechatMiniprogram.Input) {
    const field = e.currentTarget.dataset.field as string
    this.setData({ [`petForm.${field}`]: e.detail.value } as Record<string, string>)
  },

  onPetFormType(e: WechatMiniprogram.PickerChange) {
    this.setData({ 'petForm.type': String(e.detail.value) === '0' ? 'CAT' : 'DOG' })
  },

  onPetFormHair(e: WechatMiniprogram.PickerChange) {
    const hairs = ['SHORT', 'MEDIUM', 'LONG']
    this.setData({ 'petForm.hair': hairs[Number(e.detail.value)] })
  },

  async submitPetForm() {
    const f = this.data.petForm
    if (!f.name.trim()) {
      wx.showToast({ title: '请填写宠物名', icon: 'none' })
      return
    }
    try {
      await createPet({
        name: f.name.trim(),
        type: f.type,
        hair: f.type === 'CAT' ? f.hair : 'SHORT',
        weight: f.weight ? parseFloat(f.weight) : null
      })
      wx.showToast({ title: '已添加' })
      this.setData({ showPetForm: false, petForm: { name: '', type: 'CAT', hair: 'SHORT', weight: '' } })
      this.loadPets()
    } catch (err) {
      showApiError(err)
    }
  },

  /* ===== 步骤 3：选时段 ===== */
  pickDate(e: WechatMiniprogram.TouchEvent) {
    const value = e.currentTarget.dataset.value as string
    this.setData({ selectedDate: value, selectedSlot: '' })
    this.loadSlots()
  },

  async loadSlots() {
    if (!this.data.selectedService) return
    try {
      const slots = await listSlots(this.data.selectedService.id, this.data.selectedDate)
      this.setData({ slots })
    } catch (e) {
      showApiError(e)
    }
  },

  pickSlot(e: WechatMiniprogram.TouchEvent) {
    const start = e.currentTarget.dataset.start as string
    this.setData({ selectedSlot: start, step: 4 })
    this.loadWallet()
  },

  /** 资金事实：余额实时拉取，不走缓存 */
  async loadWallet() {
    try {
      const w = await wallet()
      this.setData({ walletInfo: w })
    } catch (e) {
      showApiError(e)
    }
  },

  /* ===== 步骤 4：担保与提交 ===== */
  pickGuarantee(e: WechatMiniprogram.TouchEvent) {
    this.setData({ guarantee: e.currentTarget.dataset.type as string })
  },

  pickCard(e: WechatMiniprogram.TouchEvent) {
    this.setData({ selectedCardId: e.currentTarget.dataset.id as SnowflakeId })
  },

  onRemarkInput(e: WechatMiniprogram.Input) {
    this.setData({ remark: e.detail.value })
  },

  goStep(e: WechatMiniprogram.TouchEvent) {
    const step = e.currentTarget.dataset.step as number
    if (step < this.data.step) {
      this.setData({ step })
    }
  },

  async submit() {
    const { selectedService, selectedPet, selectedDate, selectedSlot, guarantee, selectedCardId, remark, quoteData } = this.data
    if (!selectedService || !selectedPet || !selectedSlot) return
    if (guarantee === 'CARD' && !selectedCardId) {
      wx.showToast({ title: '请选择用于担保的次卡', icon: 'none' })
      return
    }
    if (guarantee === 'BALANCE' && quoteData && this.data.walletInfo && this.data.walletInfo.available < quoteData.price) {
      wx.showToast({ title: '可用余额不足，请先充值或改用次卡', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      const result = await createAppointment({
        serviceId: selectedService.id,
        petId: selectedPet.id,
        slotDate: selectedDate,
        startTime: selectedSlot,
        guaranteeType: guarantee,
        cardId: guarantee === 'CARD' ? selectedCardId ?? undefined : undefined,
        remark: remark || undefined
      })
      this.setData({ result, step: 5 })
    } catch (e) {
      showApiError(e)
    } finally {
      this.setData({ submitting: false })
    }
  },

  restart() {
    this.setData({
      step: 1,
      selectedService: null,
      selectedPet: null,
      quoteData: null,
      selectedSlot: '',
      guarantee: 'BALANCE',
      selectedCardId: null,
      remark: '',
      result: null
    })
  },

  goMyAppointments() {
    wx.navigateTo({ url: '/subpages/appointments/appointments' })
  }
})
