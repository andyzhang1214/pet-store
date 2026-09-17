import { themeBehavior } from '../../behaviors/theme'
import { listPets, createPet, updatePet, deletePet, Pet } from '../../api/member'
import { showApiError } from '../../utils/request'
import type { SnowflakeId } from '../../utils/request'

Page({
  behaviors: [themeBehavior],
  data: {
    pets: [] as Pet[],
    showForm: false,
    editingId: null as SnowflakeId | null,
    form: { name: '', type: 'CAT', hair: 'SHORT', weight: '' }
  },
  onShow() {
    this.load()
  },
  async load() {
    try {
      this.setData({ pets: await listPets() })
    } catch (e) {
      showApiError(e)
    }
  },
  openAdd() {
    this.setData({ showForm: true, editingId: null, form: { name: '', type: 'CAT', hair: 'SHORT', weight: '' } })
  },
  openEdit(e: WechatMiniprogram.TouchEvent) {
    // id 一律字符串：dataset 本来就是字符串，与后端雪花 id 的序列化契约一致
    const pet = this.data.pets.find((p) => p.id === (e.currentTarget.dataset.id as SnowflakeId))
    if (!pet) return
    this.setData({
      showForm: true,
      editingId: pet.id,
      form: { name: pet.name, type: pet.type, hair: pet.hair || 'SHORT', weight: pet.weight ? String(pet.weight) : '' }
    })
  },
  closeForm() {
    this.setData({ showForm: false })
  },
  /**
   * 弹层本体的 catchtap 占位，**空的，永远不要往里写逻辑，也不要删**。
   *
   * 遮罩上的 bindtap="closeForm" 管「点空白处关闭」，而弹层内部任何一次 tap
   * （点输入框、点 picker、点保存）都会沿着 DOM 往上冒泡到遮罩，
   * 于是「点一下输入框 → 弹层立刻关闭 → 根本没法输入」。
   * catchtap 的作用是在弹层这一层截断冒泡；但 catchtap="" 属于无效绑定，
   * 微信不会注册这个 catch，所以必须指向一个真实存在的方法，哪怕它是空的。
   */
  noop() {},
  onInput(e: WechatMiniprogram.Input) {
    this.setData({ [`form.${e.currentTarget.dataset.field}`]: e.detail.value } as Record<string, string>)
  },
  onType(e: WechatMiniprogram.PickerChange) {
    this.setData({ 'form.type': String(e.detail.value) === '0' ? 'CAT' : 'DOG' })
  },
  onHair(e: WechatMiniprogram.PickerChange) {
    const hairs = ['SHORT', 'MEDIUM', 'LONG']
    this.setData({ 'form.hair': hairs[Number(e.detail.value)] })
  },
  async save() {
    const { form, editingId } = this.data
    if (!form.name.trim()) {
      wx.showToast({ title: '请填写宠物名', icon: 'none' })
      return
    }
    const body = {
      name: form.name.trim(),
      type: form.type,
      hair: form.type === 'CAT' ? form.hair : 'SHORT',
      weight: form.weight ? parseFloat(form.weight) : null
    }
    try {
      if (editingId) {
        await updatePet(editingId, body)
      } else {
        await createPet(body)
      }
      wx.showToast({ title: '已保存' })
      this.setData({ showForm: false })
      this.load()
    } catch (e) {
      showApiError(e)
    }
  },
  async remove(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id as SnowflakeId
    const { confirm } = await wx.showModal({ title: '删除宠物', content: '删除后健康档案将无法查看，确定删除？', confirmColor: '#FF4D4F' })
    if (!confirm) return
    try {
      await deletePet(id)
      this.load()
    } catch (err) {
      showApiError(err)
    }
  }
})
