import type { CallRecord, CallRecordDetail, UsageStats } from '../types'
import { getAuthHeaders } from './auth'

const API_BASE = '/api'

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

interface PageResult {
  list: CallRecord[]
  total: number
  page: number
  pageSize: number
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

/** 获取通话记录列表（分页） */
export async function fetchCallRecords(
  page = 1,
  pageSize = 10,
  assistantId?: string
): Promise<PageResult> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (assistantId) params.set('assistantId', assistantId)
  const response = await fetch(`${API_BASE}/call-records?${params.toString()}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<PageResult>(response)
}

/** 获取单次通话详情（含消息列表） */
export async function fetchCallRecordDetail(id: string): Promise<CallRecordDetail> {
  const response = await fetch(`${API_BASE}/call-records/${id}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<CallRecordDetail>(response)
}

/** 上传通话录音（MediaRecorder 录制的 webm blob；multipart 需显式去除 JSON Content-Type） */
export async function uploadRecording(id: string, blob: Blob): Promise<void> {
  const token = localStorage.getItem('token')
  const headers: Record<string, string> = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const form = new FormData()
  form.append('file', blob, `${id}.webm`)
  const response = await fetch(`${API_BASE}/call-records/${id}/recording`, {
    method: 'POST',
    headers,
    body: form,
  })
  if (!response.ok) {
    throw new Error('上传录音失败')
  }
  const result: ApiResponse<null> = await response.json()
  if (result.code !== 200) {
    throw new Error(result.message || '上传录音失败')
  }
}

/** 拉取通话录音 blob（供回放播放器使用） */
export async function fetchRecordingBlob(id: string): Promise<Blob> {
  const response = await fetch(`${API_BASE}/call-records/${id}/recording`, {
    headers: getAuthHeaders(),
  })
  if (!response.ok) {
    throw new Error('获取录音失败')
  }
  return response.blob()
}

/** 获取用量统计（day/week/month） */
export async function fetchUsageStats(range: 'day' | 'week' | 'month' = 'week'): Promise<UsageStats> {
  const response = await fetch(`${API_BASE}/stats/usage?range=${range}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<UsageStats>(response)
}
