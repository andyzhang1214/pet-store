import { get, post, put, del } from '../utils/request'
import type { SnowflakeId } from '../utils/request'

export interface Pet {
  id: SnowflakeId
  memberId: SnowflakeId
  name: string
  type: string
  size: string | null
  hair: string | null
  weight: number | null
  birthday: string | null
  gender: string | null
  createdAt: string
}

export interface HealthLog {
  id: SnowflakeId
  petId: SnowflakeId
  logType: string
  logDate: string
  nextDue: string | null
  weightValue: number | null
  source: string
  operator: string | null
  remark: string | null
}

/** 到期状态：与后端 HealthArchiveService.DUE_* 常量严格一致（SCHEDULED / DUE_SOON / OVERDUE） */
export type DueStatus = 'SCHEDULED' | 'DUE_SOON' | 'OVERDUE'

export interface DueItem {
  lastDate: string | null
  nextDue: string | null
  daysLeft: number
  status: DueStatus
}

export interface CareItem {
  lastDate: string | null
  daysAgo: number
}

/**
 * 健康档案 VO —— 字段名以后端 HealthArchiveService.ArchiveVO 为唯一准绳。
 * 历史教训（审查 M-2）：前端曾用 vaccineDue / rabiesDue / dewormDue / logs，
 * 与后端 vaccine / worm / recentLogs 完全对不上，导致整页空白。
 */
export interface ArchiveVO {
  vaccine: DueItem | null
  worm: DueItem | null
  weightTrend: number[]
  lastBath: CareItem | null
  lastGroom: CareItem | null
  recentLogs: HealthLog[]
}

export const listPets = () => get<Pet[]>('/api/v1/pets')

export const createPet = (pet: Partial<Pet>) => post<Pet>('/api/v1/pets', pet)

export const updatePet = (id: SnowflakeId, patch: Partial<Pet>) => put<Pet>(`/api/v1/pets/${id}`, patch)

export const deletePet = (id: SnowflakeId) => del<void>(`/api/v1/pets/${id}`)

export const petHealth = (petId: SnowflakeId) => get<ArchiveVO>(`/api/v1/pets/${petId}/health`)
