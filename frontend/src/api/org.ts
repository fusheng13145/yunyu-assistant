import type { Org, OrgMember } from '../types'
import { request } from './auth'

const API_BASE = '/api'

/** 创建组织 */
export function createOrg(name: string, description?: string): Promise<Org> {
  return request<Org>(`${API_BASE}/orgs`, {
    method: 'POST',
    body: JSON.stringify({ name, description }),
  })
}

/** 查询我的组织列表 */
export function fetchOrgs(): Promise<Org[]> {
  return request<Org[]>(`${API_BASE}/orgs`)
}

/** 查询组织成员列表 */
export function fetchOrgMembers(orgId: string): Promise<OrgMember[]> {
  return request<OrgMember[]>(`${API_BASE}/orgs/${orgId}/members`)
}

/** 添加成员（按用户名，角色 editor/viewer） */
export function addOrgMember(orgId: string, username: string, role: string): Promise<OrgMember> {
  return request<OrgMember>(`${API_BASE}/orgs/${orgId}/members`, {
    method: 'POST',
    body: JSON.stringify({ username, role }),
  })
}

/** 修改成员角色 */
export async function changeOrgMemberRole(orgId: string, targetUserId: string, role: string): Promise<void> {
  await request<void>(`${API_BASE}/orgs/${orgId}/members/${targetUserId}`, {
    method: 'PUT',
    body: JSON.stringify({ role }),
  })
}

/** 移除成员（owner 移除他人 / 本人退出） */
export async function removeOrgMember(orgId: string, targetUserId: string): Promise<void> {
  await request<void>(`${API_BASE}/orgs/${orgId}/members/${targetUserId}`, { method: 'DELETE' })
}

/** 删除组织（仅 owner） */
export async function deleteOrg(orgId: string): Promise<void> {
  await request<void>(`${API_BASE}/orgs/${orgId}`, { method: 'DELETE' })
}
