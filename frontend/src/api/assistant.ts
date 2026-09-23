import type { Assistant, CreateAssistantData, UpdateAssistantData, VoiceInfo, ModelInfo, ToolInfo } from '../types'
import { request } from './auth'

const API_BASE = '/api'

/** 分页查询助手列表（支持关键词搜索名称/描述） */
export interface AssistantPage {
  list: Assistant[]
  total: number
  page: number
  pageSize: number
}

export function fetchAssistantsPage(page: number, pageSize: number, keyword?: string): Promise<AssistantPage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (keyword && keyword.trim()) {
    params.set('keyword', keyword.trim())
  }
  return request<AssistantPage>(`${API_BASE}/assistants/page?${params.toString()}`)
}

export function fetchAssistant(id: string): Promise<Assistant> {
  return request<Assistant>(`${API_BASE}/assistants/${id}`)
}

export function createAssistant(data: CreateAssistantData): Promise<Assistant> {
  return request<Assistant>(`${API_BASE}/assistants`, { method: 'POST', body: JSON.stringify(data) })
}

export async function updateAssistant(data: UpdateAssistantData): Promise<void> {
  await request<void>(`${API_BASE}/assistants`, { method: 'PUT', body: JSON.stringify(data) })
}

export async function deleteAssistant(id: string): Promise<void> {
  await request<void>(`${API_BASE}/assistants/${id}`, { method: 'DELETE' })
}

export function fetchVoices(): Promise<VoiceInfo[]> {
  return request<VoiceInfo[]>(`${API_BASE}/voices`)
}

export function fetchModels(): Promise<ModelInfo[]> {
  return request<ModelInfo[]>(`${API_BASE}/models`)
}

/** 当前实际注册（配置已就绪）的 AI 工具列表，供助手"可用工具"白名单勾选 */
export function fetchTools(): Promise<ToolInfo[]> {
  return request<ToolInfo[]>(`${API_BASE}/tools`)
}
