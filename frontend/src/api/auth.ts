import type { LoginData, RegisterData, AuthResponse } from '../types'

const API_BASE = '/api'

const TOKEN_KEY = 'token'
const REFRESH_KEY = 'refreshToken'
const USER_ID_KEY = 'userId'
const USERNAME_KEY = 'username'
const ROLE_KEY = 'role'

/** 提前续期窗口：访问令牌剩余寿命不足此值即换发，避免请求边界上正好 401 */
const RENEW_SKEW_MS = 60_000

interface ApiResponse<T> {
  code: number
  message: string
  data?: T
}

/** 统一响应体解包：HTTP 层错误与服务端 code≠200 都抛业务消息 */
export async function parseResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({ message: '请求失败' }))
    throw new Error(errorData.message || '请求失败')
  }
  const result = (await response.json()) as ApiResponse<T>
  if (result.code !== 200) {
    throw new Error(result.message || '请求失败')
  }
  return result.data as T
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** 登录/注册/续期三处共用的会话落盘（role 必须随行，否则前端权限判定会滞后于服务端） */
export function saveSession(session: AuthResponse): void {
  localStorage.setItem(TOKEN_KEY, session.token)
  localStorage.setItem(REFRESH_KEY, session.refreshToken)
  localStorage.setItem(USER_ID_KEY, session.userId)
  localStorage.setItem(USERNAME_KEY, session.username)
  localStorage.setItem(ROLE_KEY, session.role || 'user')
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(USER_ID_KEY)
  localStorage.removeItem(USERNAME_KEY)
  localStorage.removeItem(ROLE_KEY)
}

function getAuthHeaders(): Record<string, string> {
  const token = getToken()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

/**
 * 只解 JWT payload 取 exp，用于本地"该不该续期"的决策。
 * 不做签名校验（那是服务端的事），解析失败一律按"不新鲜"处理走续期。
 */
function expiresAtMs(token: string): number | null {
  const payload = token.split('.')[1]
  if (!payload) return null
  try {
    const decoded = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))) as { exp?: unknown }
    return typeof decoded.exp === 'number' ? decoded.exp * 1000 : null
  } catch {
    return null
  }
}

export function isTokenFresh(token: string | null, now: number = Date.now()): boolean {
  if (!token) return false
  const exp = expiresAtMs(token)
  // 解不出 exp 时不妄判：按"新鲜"处理，交由服务端决定（误判成过期会白踢人下线）
  if (exp === null) return true
  return exp - now > RENEW_SKEW_MS
}

/** 仅在能明确解出 exp 且已过期时返回 true；解不出的令牌不判过期 */
export function isTokenExpired(token: string | null, now: number = Date.now()): boolean {
  if (!token) return true
  const exp = expiresAtMs(token)
  return exp !== null && exp <= now
}

let renewing: Promise<boolean> | null = null

async function postRefresh(refreshTokenValue: string): Promise<boolean> {
  try {
    const session = await refreshToken(refreshTokenValue)
    if (!session?.token) return false
    saveSession(session)
    return true
  } catch {
    return false
  }
}

async function renew(): Promise<boolean> {
  const stored = localStorage.getItem(REFRESH_KEY)
  if (!stored) return false
  if (await postRefresh(stored)) return true
  // 服务端换发即拉黑旧令牌，故"另一个标签页已经续期成功"会表现为本次失败：
  // 重读存储令牌，若已变化就直接沿用新会话，而不是把用户踢回登录页
  const rotated = localStorage.getItem(REFRESH_KEY)
  return rotated !== null && rotated !== stored
}

/** 单飞：并发 401 只允许一次换发，避免轮换中的旧令牌互相作废 */
export function renewToken(): Promise<boolean> {
  if (!renewing) {
    renewing = renew().finally(() => {
      renewing = null
    })
  }
  return renewing
}

/** 供 WebSocket 建链前使用：令牌将过期则先续期，返回当前可用令牌（可能仍已过期） */
export async function ensureFreshToken(): Promise<string | null> {
  const token = getToken()
  if (!token) return null
  if (isTokenFresh(token)) return token
  return (await renewToken()) ? getToken() : token
}

function withAuth(init: RequestInit, token: string | null): RequestInit {
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const isForm = typeof FormData !== 'undefined' && init.body instanceof FormData
  if (init.body != null && !isForm && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  return { ...init, headers }
}

/** 会话彻底失效：清本地态并回登录页（已在登录页则不打断用户） */
function handleAuthLost(): void {
  clearSession()
  if (!window.location.pathname.startsWith('/login')) {
    window.location.assign('/login?reason=expired')
  }
}

/** 带鉴权的请求：过期令牌先续期再发；服务端仍回 401 时换发一次并重放本请求 */
export async function authFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const send = (token: string | null) => fetch(url, withAuth(init, token))
  let response = await send(await ensureFreshToken())
  if (response.status !== 401) return response
  if (!(await renewToken())) {
    handleAuthLost()
    return response
  }
  response = await send(getToken())
  if (response.status === 401) handleAuthLost()
  return response
}

export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  return parseResponse<T>(await authFetch(url, init))
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

/** 用 refresh token 换取新的访问令牌与刷新令牌（轮换，旧令牌服务端即刻作废） */
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
