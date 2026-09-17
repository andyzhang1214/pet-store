import { get } from '../utils/request'
import type { SnowflakeId } from '../utils/request'

/** 与后端 WalletVO 字段严格一致（审查 M-2：曾多出 principalBalance / giftBalance 两个不存在的字段） */
export interface WalletVO {
  balance: number
  frozenAmount: number
  available: number
  refundablePrincipal: number
}

export interface BalanceLedger {
  id: SnowflakeId
  memberId: SnowflakeId
  bizType: string
  principalAmt: number
  giftAmt: number
  balanceAfter: number
  frozenAfter: number
  bizNo: string
  createdAt: string
}

/** total 也是字符串：后端 Long 统一走字符串序列化（见 utils/request.ts 的 SnowflakeId） */
export interface LedgerPage {
  records: BalanceLedger[]
  total: string
  current: number
  size: number
}

/**
 * 我的次卡：字段与后端 MemberCardVO 一致。
 * refundable 由服务端判定（未使用、无预扣、未过期），前端不自己算。
 * paidAmount 是购买实付，退款以此为准而非卡类型现行价。
 */
export interface MemberCardVO {
  id: SnowflakeId
  cardNo: string
  name: string
  totalTimes: number
  remainTimes: number
  preDeductCount: number
  validUntil: string
  status: string
  paidAmount: number
  refundable: boolean
}

/** 资金事实：不缓存，每次进页实时拉取 */
export const wallet = () => get<WalletVO>('/api/v1/wallet')

export const ledger = (page = 1, size = 20) =>
  get<LedgerPage>(`/api/v1/wallet/ledger?page=${page}&size=${size}`)

export const myCards = () => get<MemberCardVO[]>('/api/v1/wallet/cards')
