import { ensureFreshToken } from '../api/auth'

interface WebSocketHandlers {
  onMessage?: (event: MessageEvent) => void
  onOpen?: (event: Event) => void
  onClose?: (event: CloseEvent) => void
  onError?: (event: Event) => void
  onReconnect?: (attempt: number) => void
}

/** 心跳间隔（毫秒），与服务端约定 30s */
const HEARTBEAT_INTERVAL = 30000
/** 最大重连次数 */
const MAX_RECONNECT_ATTEMPTS = 5

export function useWebSocket(url: string, handlers: WebSocketHandlers = {}) {
  let ws: WebSocket | null = null
  let authSent = false
  let manuallyClosed = false
  let reconnectAttempts = 0
  let heartbeatTimer: number | null = null

  const startHeartbeat = () => {
    stopHeartbeat()
    heartbeatTimer = window.setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'ping' }))
      }
    }, HEARTBEAT_INTERVAL)
  }

  const stopHeartbeat = () => {
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  const connect = async () => {
    // 令牌在每次建链/重连时重取：页面存活期跨过一次静默续期后，重连不能再携带旧令牌
    const token = await ensureFreshToken()
    if (manuallyClosed) return
    ws = new WebSocket(url)
    authSent = false

    ws.onopen = (event) => {
      reconnectAttempts = 0
      // 连接建立后立即发送认证消息
      if (token && !authSent) {
        ws?.send(JSON.stringify({ type: 'auth', token }))
        authSent = true
      }
      handlers.onOpen?.(event as Event)
      startHeartbeat()
    }

    ws.onmessage = (event) => {
      // 心跳回包，不抛给业务层
      try {
        const data = JSON.parse(event.data)
        if (data && data.type === 'pong') return
      } catch {
        // 非 JSON 消息，继续交给业务层处理
      }
      handlers.onMessage?.(event)
    }

    ws.onclose = (event) => {
      stopHeartbeat()
      handlers.onClose?.(event)
      // 非主动关闭时指数退避重连
      if (!manuallyClosed && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 16000)
        reconnectAttempts++
        handlers.onReconnect?.(reconnectAttempts)
        setTimeout(connect, delay)
      }
    }

    ws.onerror = handlers.onError ?? (() => {})
  }

  /** 发送消息，自动序列化对象 */
  const send = (data: string | object) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return
    const payload = typeof data === 'string' ? data : JSON.stringify(data)
    ws.send(payload)
  }

  /** 主动关闭连接（不触发重连） */
  const close = () => {
    manuallyClosed = true
    stopHeartbeat()
    if (ws) {
      ws.close()
      ws = null
    }
  }

  connect()

  return {
    send,
    close,
    get instance() { return ws }
  }
}
