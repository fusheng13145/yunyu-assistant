import type { ChatSession } from '../types'
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

/** 创建会话 */
export async function createSession(assistantId: string, title?: string): Promise<ChatSession> {
  const response = await fetch(`${API_BASE}/sessions`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: JSON.stringify({ assistantId, title }),
  })
  return parseResponse<ChatSession>(response)
}

/** 查询当前用户的会话列表（可按助手过滤） */
export async function fetchSessions(assistantId?: string): Promise<ChatSession[]> {
  const query = assistantId ? `?assistantId=${encodeURIComponent(assistantId)}` : ''
  const response = await fetch(`${API_BASE}/sessions${query}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<ChatSession[]>(response)
}

/** 更新会话（标题 / 置顶） */
export async function updateSession(id: string, data: { title?: string; isPinned?: boolean }): Promise<void> {
  const response = await fetch(`${API_BASE}/sessions/${id}`, {
    method: 'PUT',
    headers: getAuthHeaders(),
    body: JSON.stringify(data),
  })
  await parseResponse<void>(response)
}

/** 删除会话 */
export async function deleteSession(id: string): Promise<void> {
  const response = await fetch(`${API_BASE}/sessions/${id}`, {
    method: 'DELETE',
    headers: getAuthHeaders(),
  })
  await parseResponse<void>(response)
}

/** 查询会话历史消息（分页，倒序最新在前；切换会话时回显，长会话惰性加载） */
export interface SessionMessagePage {
  list: ChatRecordMessage[]
  total: number
  page: number
  pageSize: number
}

export async function fetchSessionMessages(id: string, page = 1, pageSize = 50): Promise<SessionMessagePage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  const response = await fetch(`${API_BASE}/sessions/${id}/messages?${params.toString()}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<SessionMessagePage>(response)
}

/** 会话历史消息（与后端 Record 实体对齐） */
export interface ChatRecordMessage {
  id: string
  role: number
  message: string
  toolName?: string
  toolArgs?: string
  toolResult?: string
  costTime?: number
  createdAt?: string
}