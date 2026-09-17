import { get, post, del } from '../utils/request'
import type { SnowflakeId } from '../utils/request'

export interface ServiceItem {
  id: SnowflakeId
  storeId: SnowflakeId
  name: string
  category: string
  durationSlots: number
  capacity: number
  status: string
}

/** 与后端 UsableCardVO 字段严格一致（审查 M-2：曾误用 cardName / remaining 导致选择次卡显示 undefined） */
export interface UsableCard {
  cardId: SnowflakeId
  cardNo: string
  name: string
  remainTimes: number
  validUntil: string
}

export interface MatchedQuote {
  specId: SnowflakeId
  specName: string
  price: number
  usableCards: UsableCard[]
}

export interface SlotVO {
  startTime: string
  capacity: number
  remaining: number
  full: boolean
  past: boolean
}

export interface ApptVO {
  id: SnowflakeId
  apptNo: string
  serviceId: SnowflakeId
  serviceName: string
  petId: SnowflakeId
  petName: string
  slotDate: string
  startTime: string
  price: number
  guaranteeType: string
  guaranteeAmount: number
  status: string
  verifyCode: string
  cancelDeadline: string
  vaccinePending: number
}

export interface CreateApptCmd {
  serviceId: SnowflakeId
  petId: SnowflakeId
  slotDate: string
  startTime: string
  guaranteeType: string
  cardId?: SnowflakeId
  remark?: string
}

export const listServices = () => get<ServiceItem[]>('/api/v1/services')

export const quote = (serviceId: SnowflakeId, petId: SnowflakeId) =>
  get<MatchedQuote>(`/api/v1/services/${serviceId}/quote?petId=${petId}`)

export const listSlots = (serviceId: SnowflakeId, date: string) =>
  get<SlotVO[]>(`/api/v1/slots?serviceId=${serviceId}&date=${date}`)

export const createAppointment = (cmd: CreateApptCmd) => post<ApptVO>('/api/v1/appointments', cmd)

export const listAppointments = (status?: string) =>
  get<ApptVO[]>(`/api/v1/appointments${status ? `?status=${status}` : ''}`)

export const cancelAppointment = (id: SnowflakeId) => del<void>(`/api/v1/appointments/${id}`)

/** 顾客自助确认到店（仅预约当天可点；重复确认后端 CAS 拦截） */
export const confirmArrival = (id: SnowflakeId) => post<void>(`/api/v1/appointments/${id}/confirm-arrival`)
