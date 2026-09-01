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


