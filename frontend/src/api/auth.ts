import type { LoginData, RegisterData, AuthResponse } from '../types'

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

export function getAuthHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

export async function login(data: LoginData): Promise<AuthResponse> {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  return parseResponse<AuthResponse>(response)
}

export async function register(data: RegisterData): Promise<AuthResponse> {
  const response = await fetch(`${API_BASE}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  return parseResponse<AuthResponse>(response)
}

/** 用 refresh token 换取新的访问令牌与刷新令牌（轮换） */
export async function refreshToken(refreshToken: string): Promise<AuthResponse> {
  const response = await fetch(`${API_BASE}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
  })
  return parseResponse<AuthResponse>(response)
}

/** 登出：服务端将当前令牌加入黑名单（携带 refreshToken 一并作废） */
export async function logout(refreshToken?: string): Promise<void> {
  const response = await fetch(`${API_BASE}/auth/logout`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: refreshToken ? JSON.stringify({ refreshToken }) : undefined,
  })
  await parseResponse<void>(response)
}


