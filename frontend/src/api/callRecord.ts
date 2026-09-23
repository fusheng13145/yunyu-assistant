import type { CallRecord, CallRecordDetail, UsageStats } from '../types'
import { authFetch, parseResponse, request } from './auth'

const API_BASE = '/api'

interface PageResult {
  list: CallRecord[]
  total: number
  page: number
  pageSize: number
}

/** 获取通话记录列表（分页） */
export function fetchCallRecords(page = 1, pageSize = 10, assistantId?: string): Promise<PageResult> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (assistantId) params.set('assistantId', assistantId)
  return request<PageResult>(`${API_BASE}/call-records?${params.toString()}`)
}

/** 获取单次通话详情（含消息列表） */
export function fetchCallRecordDetail(id: string): Promise<CallRecordDetail> {
  return request<CallRecordDetail>(`${API_BASE}/call-records/${id}`)
}

/** 上传通话录音（MediaRecorder 录制的 webm blob；multipart 由 authFetch 跳过 JSON Content-Type） */
export async function uploadRecording(id: string, blob: Blob): Promise<void> {
  const form = new FormData()
  form.append('file', blob, `${id}.webm`)
  const response = await authFetch(`${API_BASE}/call-records/${id}/recording`, {
    method: 'POST',
    body: form,
  })
  await parseResponse<void>(response)
}

/** 拉取通话录音 blob（供回放播放器使用） */
export async function fetchRecordingBlob(id: string): Promise<Blob> {
  const response = await authFetch(`${API_BASE}/call-records/${id}/recording`)
  if (!response.ok) {
    throw new Error('获取录音失败')
  }
  return response.blob()
}

/** 获取用量统计（day/week/month） */
export function fetchUsageStats(range: 'day' | 'week' | 'month' = 'week'): Promise<UsageStats> {
  return request<UsageStats>(`${API_BASE}/stats/usage?range=${range}`)
}
