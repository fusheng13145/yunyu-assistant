interface WebSocketHandlers {
  onMessage?: (event: MessageEvent) => void
  onOpen?: (event: Event) => void
  onClose?: (event: CloseEvent) => void
  onError?: (event: Event) => void
}

export function useWebSocket(url: string, handlers: WebSocketHandlers = {}) {
  let ws: WebSocket | null = null
  const token = localStorage.getItem('token')

  const connect = () => {
    // 握手阶段通过子协议传递 token
    ws = token ? new WebSocket(url, [token]) : new WebSocket(url)

    ws.onopen = handlers.onOpen ?? (() => {})
    ws.onmessage = handlers.onMessage ?? (() => {})
    ws.onclose = handlers.onClose ?? (() => {})
    ws.onerror = handlers.onError ?? (() => {})
  }

  /** 发送消息，自动序列化对象 */
  const send = (data: string | object) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return
    const payload = typeof data === 'string' ? data : JSON.stringify(data)
    ws.send(payload)
  }

  /** 主动关闭连接 */
  const close = () => {
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