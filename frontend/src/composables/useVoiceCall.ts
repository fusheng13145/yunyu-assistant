import { ref } from 'vue'
import { useWebRTC } from './useWebRTC'
import { useWebSocket } from '../utils/websocket'
import type { AsrDeltaData } from '../types'

export interface UseVoiceCallOptions {
  assistantId: () => string | undefined | null
  onAssistantMessage?: (data: { segment: string; streamEnd: boolean }) => void
  onQueryEnd?: (data: { message?: string; costTime?: number; knowledgebase?: any }) => void
  onHangup?: () => void
  onNotification?: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void
  onIsTypingChange?: (value: boolean) => void
  onIsFirstOfStreamChange?: (value: boolean) => void
}

export function useVoiceCall(options: UseVoiceCallOptions) {
  const webrtc = useWebRTC()
  const audioLevel = webrtc.audioLevel
  const voiceCallActive = ref(false)
  const asrText = ref('')

  let voiceWs: ReturnType<typeof useWebSocket> | null = null

  const notify = options.onNotification || (() => {})

  const startVoiceCall = async () => {
    const id = options.assistantId()
    if (!id) return

    try {
      asrText.value = ''
      const offerSDP = await webrtc.createOffer()

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const host = window.location.host
      const wsUrl = `${protocol}//${host}/ws-voice/${id}`

      voiceWs = useWebSocket(wsUrl, {
        onOpen: () => {
          voiceWs?.send({ type: 'offer', sdp: offerSDP })
        },
        onMessage: async (event) => {
          try {
            const data = JSON.parse(event.data)

            if (data.type === 'webrtc_answer') {
              await webrtc.handleAnswer(data.data)
              voiceCallActive.value = true
              voiceWs?.send({ type: 'webrtc_connected' })
              notify('语音通话已连接', 'success')
            } else if (data.type === 'asr_delta') {
              const asrData = data.data as AsrDeltaData
              asrText.value = asrData.text
            } else if (data.type === 'assistant_message') {
              const answer = data.data
              if (answer.streamEnd) {
                options.onIsTypingChange?.(false)
                options.onIsFirstOfStreamChange?.(true)
              } else {
                options.onAssistantMessage?.(answer)
              }
            } else if (data.type === 'query_end') {
              const queryData = data.data
              options.onIsTypingChange?.(false)
              options.onIsFirstOfStreamChange?.(true)
              options.onQueryEnd?.(queryData)
              asrText.value = ''
            } else if (data.type === 'hangup') {
              endVoiceCall()
              notify('对方已挂断', 'info')
            }
          } catch (e) {
            console.error('语音消息解析失败', e)
          }
        },
        onClose: () => {
          if (voiceCallActive.value) {
            endVoiceCall()
          }
        },
        onError: (err) => {
          console.error('语音WebSocket错误', err)
          endVoiceCall()
          notify('语音连接失败', 'error')
        },
      })
    } catch (error) {
      console.error('启动语音通话失败:', error)
      notify('启动语音通话失败，请检查麦克风权限', 'error')
      webrtc.hangup()
    }
  }

  const endVoiceCall = () => {
    if (voiceWs) {
      voiceWs.send({ type: 'hangup' })
      voiceWs.close()
      voiceWs = null
    }
    webrtc.hangup()
    voiceCallActive.value = false
    asrText.value = ''
    options.onHangup?.()
  }

  const cleanup = () => {
    if (voiceWs) {
      voiceWs.send({ type: 'hangup' })
      voiceWs.close()
      voiceWs = null
    }
    webrtc.hangup()
    voiceCallActive.value = false
    asrText.value = ''
  }

  return {
    audioLevel,
    voiceCallActive,
    asrText,
    startVoiceCall,
    endVoiceCall,
    cleanup,
  }
}
