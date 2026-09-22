import type { Assistant, CreateAssistantData, UpdateAssistantData, VoiceInfo, ModelInfo } from '../types'
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

/** 分页查询助手列表（支持关键词搜索名称/描述） */
export interface AssistantPage {
  list: Assistant[]
  total: number
  page: number
  pageSize: number
}

export async function fetchAssistantsPage(page: number, pageSize: number, keyword?: string): Promise<AssistantPage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (keyword && keyword.trim()) {
    params.set('keyword', keyword.trim())
  }
  const response = await fetch(`${API_BASE}/assistants/page?${params.toString()}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<AssistantPage>(response)
}

export async function fetchAssistant(id: string): Promise<Assistant> {
  const response = await fetch(`${API_BASE}/assistants/${id}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<Assistant>(response)
}

export async function createAssistant(data: CreateAssistantData): Promise<Assistant> {
  const response = await fetch(`${API_BASE}/assistants`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: JSON.stringify(data),
  })
  return parseResponse<Assistant>(response)
}

export async function updateAssistant(data: UpdateAssistantData): Promise<void> {
  const response = await fetch(`${API_BASE}/assistants`, {
    method: 'PUT',
    headers: getAuthHeaders(),
    body: JSON.stringify(data),
  })
  await parseResponse<void>(response)
}

export async function deleteAssistant(id: string): Promise<void> {
  const response = await fetch(`${API_BASE}/assistants/${id}`, {
    method: 'DELETE',
    headers: getAuthHeaders(),
  })
  await parseResponse<void>(response)
}

export async function fetchVoices(): Promise<VoiceInfo[]> {
  const response = await fetch(`${API_BASE}/voices`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<VoiceInfo[]>(response)
}

export async function fetchModels(): Promise<ModelInfo[]> {
  const response = await fetch(`${API_BASE}/models`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<ModelInfo[]>(response)
}
