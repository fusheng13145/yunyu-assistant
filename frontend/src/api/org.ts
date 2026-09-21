import type { Org, OrgMember } from '../types'
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

/** 创建组织 */
export async function createOrg(name: string, description?: string): Promise<Org> {
  const response = await fetch(`${API_BASE}/orgs`, {
    method: 'POST',
    headers: { ...getAuthHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description }),
  })
  return parseResponse<Org>(response)
}

/** 查询我的组织列表 */
export async function fetchOrgs(): Promise<Org[]> {
  const response = await fetch(`${API_BASE}/orgs`, { headers: getAuthHeaders() })
  return parseResponse<Org[]>(response)
}

/** 查询组织成员列表 */
export async function fetchOrgMembers(orgId: string): Promise<OrgMember[]> {
  const response = await fetch(`${API_BASE}/orgs/${orgId}/members`, { headers: getAuthHeaders() })
  return parseResponse<OrgMember[]>(response)
}

/** 添加成员（按用户名，角色 editor/viewer） */
export async function addOrgMember(orgId: string, username: string, role: string): Promise<OrgMember> {
  const response = await fetch(`${API_BASE}/orgs/${orgId}/members`, {
    method: 'POST',
    headers: { ...getAuthHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, role }),
  })
  return parseResponse<OrgMember>(response)
}

/** 修改成员角色 */
export async function changeOrgMemberRole(orgId: string, targetUserId: string, role: string): Promise<void> {
  const response = await fetch(`${API_BASE}/orgs/${orgId}/members/${targetUserId}`, {
    method: 'PUT',
    headers: { ...getAuthHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  })
  await parseResponse<void>(response)
}

/** 移除成员（owner 移除他人 / 本人退出） */
export async function removeOrgMember(orgId: string, targetUserId: string): Promise<void> {
  const response = await fetch(`${API_BASE}/orgs/${orgId}/members/${targetUserId}`, {
    method: 'DELETE',
    headers: getAuthHeaders(),
  })
  await parseResponse<void>(response)
}

/** 删除组织（仅 owner） */
export async function deleteOrg(orgId: string): Promise<void> {
  const response = await fetch(`${API_BASE}/orgs/${orgId}`, {
    method: 'DELETE',
    headers: getAuthHeaders(),
  })
  await parseResponse<void>(response)
}