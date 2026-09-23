import type { User } from '../types'
import { request } from './auth'

const API_BASE = '/api'

/** 平台用量总览 */
export interface AdminOverview {
  userCount: number
  assistantCount: number
  callRecordCount: number
  messageCount: number
  sessionCount: number
  auditLogCount: number
}

export function fetchOverview(): Promise<AdminOverview> {
  return request<AdminOverview>(`${API_BASE}/admin/overview`)
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

export function fetchAuditLogs(page: number, pageSize: number): Promise<AdminAuditLogPage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  return request<AdminAuditLogPage>(`${API_BASE}/admin/audit-logs?${params.toString()}`)
}

/** 用户列表分页 */
export interface AdminUserPage {
  list: User[]
  total: number
  page: number
  pageSize: number
}

export function fetchUsers(page: number, pageSize: number, keyword?: string): Promise<AdminUserPage> {
  const params = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  if (keyword && keyword.trim()) {
    params.set('keyword', keyword.trim())
  }
  return request<AdminUserPage>(`${API_BASE}/admin/users?${params.toString()}`)
}

/** 单表归档状态（数据归档 Tab） */
export interface ArchiveTableStat {
  total: number
  expired: number
  retentionDays: number
}

/** 数据归档概览 */
export interface ArchiveOverview {
  records: ArchiveTableStat
  callRecords: ArchiveTableStat
  auditLogs: ArchiveTableStat
  scheduleEnabled: boolean
  cron: string
}

/** 归档执行结果 */
export interface ArchiveRunResult {
  recordsArchived: number
  callRecordsArchived: number
  auditLogsArchived: number
  recordingsDeleted: number
  recordingsFailed: number
  startedAt: string
  finishedAt: string
}

export function fetchArchiveOverview(): Promise<ArchiveOverview> {
  return request<ArchiveOverview>(`${API_BASE}/admin/archive/overview`)
}

export function runArchive(): Promise<ArchiveRunResult> {
  return request<ArchiveRunResult>(`${API_BASE}/admin/archive/run`, { method: 'POST' })
}

/** 配额配置（与后端 Quota 实体对齐；dailyCallSecLimit 为 Long，其余整型） */
export interface AdminQuota {
  id?: string
  scopeType: string
  scopeId: string
  assistantLimit: number | null
  dailyCallLimit: number | null
  dailyCallSecLimit: number | null
  dailyMsgLimit: number | null
  updatedAt?: string
}

/** 环境变量兜底配额：scopeType / scopeId 为 null，表示"未单独配置的 org/user 生效的就是这份" */
export function fetchQuotaDefaults(): Promise<AdminQuota> {
  return request<AdminQuota>(`${API_BASE}/admin/quotas/defaults`)
}

export function fetchQuotas(): Promise<AdminQuota[]> {
  return request<AdminQuota[]>(`${API_BASE}/admin/quotas`)
}

/** UPSERT 入参：省略的维度后端不修改；新建配置行时后端先用环境变量兜底值补齐四项 */
export interface QuotaUpsertPayload {
  scopeType: string
  scopeId: string
  assistantLimit?: number | null
  dailyCallLimit?: number | null
  dailyCallSecLimit?: number | null
  dailyMsgLimit?: number | null
}

export async function upsertQuota(payload: QuotaUpsertPayload): Promise<void> {
  await request<void>(`${API_BASE}/admin/quotas`, { method: 'PUT', body: JSON.stringify(payload) })
}
