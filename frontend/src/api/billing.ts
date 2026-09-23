import { request } from './auth'

const API_BASE = '/api'

/** 用量账单（P2-10）：配额 / 当前用量 / 剩余量 */
export interface QuotaUsage {
  period: string
  quota: {
    assistantLimit: number
    dailyCallLimit: number
    dailyCallSecLimit: number
    dailyMsgLimit: number
  }
  current: {
    assistantCount: number
    dailyCallCount: number
    dailyCallSec: number
    dailyMsgCount: number
  }
  remaining: {
    assistantRemaining: number
    dailyCallRemaining: number
    dailyCallSecRemaining: number
    dailyMsgRemaining: number
  }
  scopeType: string
  scopeId: string
}

export function fetchUsage(): Promise<QuotaUsage> {
  return request<QuotaUsage>(`${API_BASE}/billing/usage`)
}
