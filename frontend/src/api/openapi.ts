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

/**
 * 变更应用能力（v2.46）：整串替换，不是增量授予——传过去的串就是改完后的全部能力。
 * 服务端会把未知值整体拒绝、把空集拒绝（要全停请吊销），返回它归一后的实际能力串。
 * 生效时机：每次请求都重新查库，所以下一个请求即生效；已在进行中的开放语音通话不受影响。
 */
export function updateAppScopes(appId: string, scopes: string): Promise<{ id: string; scopes: string }> {
  return request<{ id: string; scopes: string }>(`${API_BASE}/openapi/apps/${appId}/scopes`, {
    method: 'PUT',
    body: JSON.stringify({ scopes }),
  })
}

/** 一条聚合结果：app 为 null 表示"认不出归属"的那一格（无 Key / 错 Key / 握手超限），全体可见 */
export interface DenialRow {
  app: string | null
  kind: string
  kindLabel: string
  count: number
}

/**
 * 开放平台拒绝台账（v2.50）：近 N 小时按「事件种类 × 应用」聚合的累计次数。
 * 口径必须知道三件事：内存实现（重启清零）、多实例各算各的份额、窗口上限 24 小时
 * （传更大的值服务端会截断并在 windowHours 里回显实际值）。
 */
export function fetchDenials(hours = 24): Promise<{ windowHours: number; rows: DenialRow[] }> {
  return request<{ windowHours: number; rows: DenialRow[] }>(`${API_BASE}/openapi/denials?hours=${hours}`)
}
