import { useWebSocket } from '../utils/websocket'
import { useChatStore } from '../stores/chat'

export interface UseChatConnectionOptions {
  assistantId: () => string | undefined | null
  onConnected?: (ws: ReturnType<typeof useWebSocket>) => void
  onNotification?: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void
}

export function useChatConnection(options: UseChatConnectionOptions) {
  let ws: ReturnType<typeof useWebSocket> | null = null
  const chatStore = useChatStore()

  const notify = options.onNotification || (() => {})

  const connect = () => {
    const id = options.assistantId()
    if (!id) return

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/ws/${id}`

    ws = useWebSocket(wsUrl, {
      onOpen: () => {
        setTimeout(() => {
          options.onConnected?.(ws!)
        }, 100)
      },
      onMessage: (event) => {
        try {
          const data = JSON.parse(event.data)

          if (data.type === 'assistant_message') {
            const answer = data.data
            if (answer.streamEnd) {
              chatStore.markStreamEnd()
            } else {
              chatStore.appendAssistantSegment(answer.segment)
            }
          } else if (data.type === 'tool_call') {
            // 工具调用消息处理
            console.log('工具调用:', data.data)
          } else if (data.type === 'tool_result') {
            // 工具调用结果处理
            console.log('工具调用结果:', data.data)
          } else if (data.type === 'error') {
            console.error('服务端错误:', data.data)
            notify('服务端暂时不可用，请稍后重试', 'error')
            chatStore.markStreamEnd()
          } else if (data.type === 'query_end') {
            const queryData = data.data
            chatStore.finalizeAssistantMessage(queryData)
          }
        } catch (e) {
          console.error('消息解析失败', e)
          chatStore.markStreamEnd()
        }
      },
      onClose: () => {
        console.log('WebSocket已关闭')
      },
      onError: (err) => {
        console.error('WebSocket错误', err)
      },
    })
  }

  const sendMessage = (content: string) => {
    if (!content.trim() || chatStore.isTyping) return
    chatStore.addUserMessage(content)
    ws?.send({ type: 'chat', content })
    chatStore.inputText = ''
    chatStore.isTyping = true
  }

  const sendPrompt = (content: string) => {
    ws?.send({ type: 'prompt', content })
  }

  const sendResetMessage = () => {
    ws?.send({ type: 'resetMessage' })
  }

  const sendSelectedKbIds = (ids: string[]) => {
    ws?.send({ type: 'selectedKbIds', ids })
  }

  const sendClose = () => {
    if (ws) {
      ws.send({ type: 'close' })
      ws.close()
      ws = null
    }
  }

  const close = () => {
    if (ws) {
      ws.close()
      ws = null
    }
  }

  const resetChat = (greeting: string) => {
    chatStore.resetMessages(greeting)
    sendResetMessage()
  }

  return {
    connect,
    sendMessage,
    sendPrompt,
    sendResetMessage,
    sendSelectedKbIds,
    sendClose,
    close,
    resetChat,
    get wsInstance() { return ws },
  }
}
