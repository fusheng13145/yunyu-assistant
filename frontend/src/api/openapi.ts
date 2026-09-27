import { request } from './auth'

const API_BASE = '/api'

/** 第三方应用列表项（与 ApiAppController.list 返回值对齐，隐藏密钥类字段） */
export interface ApiAppItem {
  id: string
  appName: string
  /** 已开通能力，逗号分隔（chat/call/voice）；自 v2.45 起真正参与服务端判定 */
  scope: string
  webhookUrl: string | null
  enabled: boolean
  createdAt: string
}

/** 创建应用返回值（appKey 与 webhookSecret 仅此一次展示；库里只留哈希，哈希不外发） */
export interface ApiAppCreated {
  id: string
  appName: string
  appKey: string
  webhookSecret: string
  webhookUrl: string | null
  /** 实体字段名为 scopes（列表接口出于历史原因键名是 scope） */
  scopes: string
  createdAt: string
}

/** 查询我的应用列表 */
export function fetchApps(): Promise<ApiAppItem[]> {
  return request<ApiAppItem[]>(`${API_BASE}/openapi/apps`)
}

/**
 * 创建第三方应用
 * @param scopes 逗号分隔的能力（chat/call/voice）；不传即只开通 chat。
 *   不认识的值服务端整体拒绝（不静默丢弃），故勾选与实得能力不会悄悄不一致
 */
export function createApp(appName: string, webhookUrl?: string, scopes?: string): Promise<ApiAppCreated> {
  return request<ApiAppCreated>(`${API_BASE}/openapi/apps`, {
    method: 'POST',
    body: JSON.stringify({ appName, webhookUrl: webhookUrl || '', scopes: scopes || '' }),
  })
}

/** 吊销应用（逻辑删除，API Key 即刻失效） */
export async function revokeApp(appId: string): Promise<void> {
  await request<void>(`${API_BASE}/openapi/apps/${appId}`, { method: 'DELETE' })
}
