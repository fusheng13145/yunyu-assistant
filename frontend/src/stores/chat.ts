import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { DisplayMessage } from '../types'

export interface NotificationState {
  show: boolean
  message: string
  type: 'success' | 'error' | 'warning' | 'info'
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<DisplayMessage[]>([])
  const inputText = ref('')
  const isTyping = ref(false)
  const isFirstOfStream = ref(true)
  const notification = ref<NotificationState>({
    show: false,
    message: '',
    type: 'info',
  })

  function showNotification(message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') {
    notification.value = { show: true, message, type }
    setTimeout(() => {
      notification.value.show = false
    }, 3000)
  }

  function resetMessages(greeting: string) {
    messages.value = [{ role: 'assistant', text: greeting }]
  }

  function addUserMessage(text: string) {
    messages.value.push({ role: 'user', text })
  }

  function appendAssistantSegment(segment: string) {
    if (isFirstOfStream.value) {
      messages.value.push({ role: 'assistant', text: segment, isStreaming: true })
      isFirstOfStream.value = false
    } else {
      const lastMsg = messages.value[messages.value.length - 1]
      if (lastMsg && lastMsg.role === 'assistant') {
        lastMsg.text += segment
      }
    }
  }

  function finalizeAssistantMessage(data: { message?: string; costTime?: number; knowledgebase?: any }) {
    isTyping.value = false
    isFirstOfStream.value = true
    const lastMsg = messages.value[messages.value.length - 1]
    if (lastMsg && lastMsg.role === 'assistant' && lastMsg.isStreaming) {
      lastMsg.text = data.message || lastMsg.text
      lastMsg.isStreaming = false
      lastMsg.costTime = data.costTime
      lastMsg.knowledgebase = data.knowledgebase
    }
  }

  function markStreamEnd() {
    isTyping.value = false
    isFirstOfStream.value = true
  }

  return {
    messages,
    inputText,
    isTyping,
    isFirstOfStream,
    notification,
    showNotification,
    resetMessages,
    addUserMessage,
    appendAssistantSegment,
    finalizeAssistantMessage,
    markStreamEnd,
  }
})
