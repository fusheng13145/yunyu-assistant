import type { User } from '../types'
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

/** 平台用量总览 */
export interface AdminOverview {
  userCount: number
  assistantCount: number
  callRecordCount: number
  messageCount: number
  sessionCount: number
  auditLogCount: number
}

export async function fetchOverview(): Promise<AdminOverview> {
  const response = await fetch(`${API_BASE}/admin/overview`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<AdminOverview>(response)
}

/** 审计日志条目（与后端 AuditLog 实体对齐） */
export interface AdminAuditLog {
  id: string
  userId: string | null
  action: string
  targetType: string | null
  targetId: string | null
  detail: string | null
  ip: string | null
  result: number
  createdAt: string
}

export interface AdminAuditLogPage {
  list: AdminAuditLog[]
  total: number
  page: number
  pageSize: number
}

export async function fetchAuditLogs(page: number, pageSize: number): Promise<AdminAuditLogPage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  const response = await fetch(`${API_BASE}/admin/audit-logs?${params.toString()}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<AdminAuditLogPage>(response)
}

/** 用户列表分页 */
export interface AdminUserPage {
  list: User[]
  total: number
  page: number
  pageSize: number
}

export async function fetchUsers(page: number, pageSize: number, keyword?: string): Promise<AdminUserPage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (keyword && keyword.trim()) {
    params.set('keyword', keyword.trim())
  }
  const response = await fetch(`${API_BASE}/admin/users?${params.toString()}`, {
    headers: getAuthHeaders(),
  })
  return parseResponse<AdminUserPage>(response)
}