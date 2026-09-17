import { themeBehavior } from '../../behaviors/theme'
import { listPets, petHealth, Pet, ArchiveVO, HealthLog, DueItem } from '../../api/member'
import { HEALTH_LOG } from '../../utils/format'
import { showApiError } from '../../utils/request'
import type { SnowflakeId } from '../../utils/request'

interface DueCard {
  key: string
  title: string
  icon: string
  due: DueItem | null
  statusText: string
  statusCls: string
  desc: string
}

interface TimelineItem extends HealthLog {
  icon: string
  typeText: string
}

Page({
  behaviors: [themeBehavior],
  data: {
    pets: [] as Pet[],
    selectedPetId: null as SnowflakeId | null,
    dueCards: [] as DueCard[],
    timeline: [] as TimelineItem[]
  },
  onShow() {
    this.loadPets()
  },
  async loadPets() {
    try {
      const pets = await listPets()
      const selected = this.data.selectedPetId && pets.some((p) => p.id === this.data.selectedPetId)
        ? this.data.selectedPetId
        : pets[0]?.id || null
      this.setData({ pets, selectedPetId: selected })
      if (selected) {
        this.loadArchive(selected)
      }
    } catch (e) {
      showApiError(e)
    }
  },
  pickPet(e: WechatMiniprogram.TouchEvent) {
    const id = e.currentTarget.dataset.id as SnowflakeId
    this.setData({ selectedPetId: id })
    this.loadArchive(id)
  },
  async loadArchive(petId: SnowflakeId) {
    try {
      const archive: ArchiveVO = await petHealth(petId)
      this.setData({
        dueCards: this.buildDueCards(archive),
        timeline: this.buildTimeline(archive)
      })
    } catch (e) {
      showApiError(e)
    }
  },
  /**
   * 到期两态看板：与后端 ArchiveVO 字段一一对应（vaccine / worm）。
   * 后端没有狂犬疫苗独立类型（疫苗统一记 VACCINE），因此不再渲染"狂犬疫苗"卡片。
   */
  buildDueCards(archive: ArchiveVO): DueCard[] {
    const def = [
      { key: 'vaccine', title: '疫苗', icon: '🛡', due: archive.vaccine },
      { key: 'worm', title: '驱虫', icon: '🐛', due: archive.worm }
    ]
    return def.map((d) => {
      const due = d.due
      let statusText = '暂无记录'
      let statusCls = 'none'
      let desc = '可到店咨询接种/驱虫'
      if (due) {
        if (due.status === 'OVERDUE') {
          statusText = '已超期'
          statusCls = 'overdue'
          desc = `已超期 ${Math.abs(due.daysLeft)} 天，建议尽快到店`
        } else if (due.status === 'DUE_SOON') {
          statusText = '即将到期'
          statusCls = 'soon'
          desc = `${due.daysLeft} 天后到期，可预约时顺路处理`
        } else {
          statusText = '正常'
          statusCls = 'ok'
          desc = due.nextDue ? `下次到期 ${due.nextDue}` : '暂无到期日'
        }
      }
      return { ...d, statusText, statusCls, desc }
    })
  },
  buildTimeline(archive: ArchiveVO): TimelineItem[] {
    return (archive.recentLogs || []).map((log) => ({
      ...log,
      icon: HEALTH_LOG[log.logType]?.icon || '🐾',
      typeText: HEALTH_LOG[log.logType]?.text || log.logType
    }))
  }
})
