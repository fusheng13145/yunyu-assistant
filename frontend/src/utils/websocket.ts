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
      // 认证帧之后按序补发握手窗口内积压的业务消息（auth 在前，顺序不乱）
      flushPendingSends()
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

  /**
   * WS 未就绪时的待发队列（v2.75 · C-144）。
   * 选完助手立刻发首条消息会撞上异步建链窗口（connect 里还要先取令牌，此刻 ws 为 null）：
   * 此前的实现直接 return 静默丢帧——用户的话蒸发、打字态永久锁死，E2E 守卫首跑抓到的正是它。
   * 上限 20：握手竞态通常毫秒级，攒满说明连接已坏，再排队只是拖延暴露。
   */
  const pendingSends: string[] = []
  const MAX_PENDING_SENDS = 20

  const flushPendingSends = () => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return
    while (pendingSends.length > 0 && ws.readyState === WebSocket.OPEN) {
      ws.send(pendingSends.shift() as string)
    }
  }

  /** 发送消息，自动序列化对象；未就绪时入队（close 之后没有未来，仍丢弃） */
  const send = (data: string | object) => {
    const payload = typeof data === 'string' ? data : JSON.stringify(data)
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      if (!manuallyClosed && pendingSends.length < MAX_PENDING_SENDS) {
        pendingSends.push(payload)
      }
      return
    }
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
