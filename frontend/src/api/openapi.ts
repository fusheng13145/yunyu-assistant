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

/** 第三方应用列表项（与 ApiAppController.list 返回值对齐，隐藏 app_key 与 webhook_secret） */
export interface ApiAppItem {
  id: string
  appName: string
  scope: string
  webhookUrl: string | null
  enabled: boolean
  createdAt: string
}

/** 创建应用返回值（app_key 与 webhook_secret 仅此一次展示） */
export interface ApiAppCreated {
  id: string
  appName: string
  appKey: string
  webhookSecret: string
  webhookUrl: string | null
  scope: string
  createdAt: string
}

/** 查询我的应用列表 */
export async function fetchApps(): Promise<ApiAppItem[]> {
  const response = await fetch(`${API_BASE}/openapi/apps`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<ApiAppItem[]>(response)
}

/** 创建第三方应用 */
export async function createApp(appName: string, webhookUrl?: string): Promise<ApiAppCreated> {
  const response = await fetch(`${API_BASE}/openapi/apps`, {
    method: 'POST',
    headers: { ...getAuthHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ appName, webhookUrl: webhookUrl || '' }),
  })
  return parseResponse<ApiAppCreated>(response)
}

/** 吊销应用（逻辑删除，API Key 即刻失效） */
export async function revokeApp(appId: string): Promise<void> {
  const response = await fetch(`${API_BASE}/openapi/apps/${appId}`, {
    method: 'DELETE',
    headers: getAuthHeaders(),
  })
  return parseResponse<void>(response)
}