import { request } from './auth'

const API_BASE = '/api'

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
export function fetchApps(): Promise<ApiAppItem[]> {
  return request<ApiAppItem[]>(`${API_BASE}/openapi/apps`)
}

/** 创建第三方应用 */
export function createApp(appName: string, webhookUrl?: string): Promise<ApiAppCreated> {
  return request<ApiAppCreated>(`${API_BASE}/openapi/apps`, {
    method: 'POST',
    body: JSON.stringify({ appName, webhookUrl: webhookUrl || '' }),
  })
}

/** 吊销应用（逻辑删除，API Key 即刻失效） */
export async function revokeApp(appId: string): Promise<void> {
  await request<void>(`${API_BASE}/openapi/apps/${appId}`, { method: 'DELETE' })
}
