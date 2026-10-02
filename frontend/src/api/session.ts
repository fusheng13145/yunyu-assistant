import type { ChatSession, KnowledgebaseInfo } from '../types'
import { request } from './auth'

const API_BASE = '/api'

/** 创建会话 */
export function createSession(assistantId: string, title?: string): Promise<ChatSession> {
  return request<ChatSession>(`${API_BASE}/sessions`, {
    method: 'POST',
    body: JSON.stringify({ assistantId, title }),
  })
}

/** 查询当前用户的会话列表（可按助手过滤） */
export function fetchSessions(assistantId?: string): Promise<ChatSession[]> {
  const query = assistantId ? `?assistantId=${encodeURIComponent(assistantId)}` : ''
  return request<ChatSession[]>(`${API_BASE}/sessions${query}`)
}

/** 更新会话（标题 / 置顶） */
export async function updateSession(id: string, data: { title?: string; isPinned?: boolean }): Promise<void> {
  await request<void>(`${API_BASE}/sessions/${id}`, { method: 'PUT', body: JSON.stringify(data) })
}

/** 删除会话 */
export async function deleteSession(id: string): Promise<void> {
  await request<void>(`${API_BASE}/sessions/${id}`, { method: 'DELETE' })
}

/** 会话历史消息（与后端 Record 实体对齐） */
export interface ChatRecordMessage {
  id: string
  role: number
  message: string
  costTime?: number
  createdAt?: string
  /** 本轮检索状态（后端 v2.41 起落库；NULL=该会话未挂知识库，故可整段缺失） */
  knowledgebase?: KnowledgebaseInfo
  /** 回合失败原因（v2.73 起落库的脱敏类别文案；NULL=该轮不是失败轮） */
  failReason?: string
  /**
   * 工具轨迹三列（v2.72 起落库；role 2/3 的行才有值，NON_NULL 之外整段缺失）。
   * tool_args 是模型参数原样 JSON；tool_result 是 JSON 字符串标量，读取侧 parseJsonScalar 解析回原文。
   */
  toolName?: string
  toolArgs?: string
  toolResult?: string
}

/** 查询会话历史消息（分页，倒序最新在前；切换会话时回显，长会话惰性加载） */
export interface SessionMessagePage {
  list: ChatRecordMessage[]
  total: number
  page: number
  pageSize: number
}

export function fetchSessionMessages(id: string, page = 1, pageSize = 50): Promise<SessionMessagePage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  return request<SessionMessagePage>(`${API_BASE}/sessions/${id}/messages?${params.toString()}`)
}
