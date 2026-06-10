interface WebSocketHandlers {
  onMessage?: (event: MessageEvent) => void
  onOpen?: (event: Event) => void
  onClose?: (event: CloseEvent) => void
  onError?: (event: Event) => void
}

export function useWebSocket(url: string, handlers: WebSocketHandlers = {}) {
  let ws: WebSocket | null = null

  const connect = () => {
    ws = new WebSocket(url)
    ws.onopen = handlers.onOpen || (() => {})
    ws.onmessage = handlers.onMessage || (() => {})
    ws.onclose = handlers.onClose || (() => {})
    ws.onerror = handlers.onError || (() => {})
  }

  const send = (data: string | object) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(typeof data === 'string' ? data : JSON.stringify(data))
    }
  }

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
    get instance() { return ws },
  }
}
