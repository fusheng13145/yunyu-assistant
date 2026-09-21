import { getAuthHeaders } from './auth'

const API_BASE = '/api'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

async function parseResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ message: '请求失败' }))
    throw new Error(errorData.message || '请求失败')
  }
  const result: ApiResponse<T> = await response.json()
  if (result.code !== 200) {
    throw new Error(result.message || '请求失败')
  }
  return result.data
}

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

export async function fetchUsage(): Promise<QuotaUsage> {
  const response = await fetch(`${API_BASE}/billing/usage`, { headers: getAuthHeaders() })
  return parseResponse<QuotaUsage>(response)
}