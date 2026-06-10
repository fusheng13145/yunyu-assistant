import { ref, onBeforeUnmount } from 'vue'

export function useWebRTC() {
  const peerConnection = ref<RTCPeerConnection | null>(null)
  const localStream = ref<MediaStream | null>(null)
  const isCallActive = ref(false)
  const isConnecting = ref(false)
  const audioLevel = ref(0)

  let audioContext: AudioContext | null = null
  let analyser: AnalyserNode | null = null
  let animationFrameId: number | null = null

  const ICE_SERVERS: RTCConfiguration = {
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' },
    ],
  }

  const createOffer = async (): Promise<string> => {
    peerConnection.value = new RTCPeerConnection(ICE_SERVERS)
    isConnecting.value = true

    try {
      localStream.value = await navigator.mediaDevices.getUserMedia({ audio: true, video: false })

      localStream.value.getTracks().forEach((track) => {
        peerConnection.value!.addTrack(track, localStream.value!)
      })

      const offer = await peerConnection.value.createOffer()
      await peerConnection.value.setLocalDescription(offer)

      return offer.sdp || ''
    } catch (error) {
      isConnecting.value = false
      cleanup()
      throw error
    }
  }

  const handleAnswer = async (answerSDP: string) => {
    if (!peerConnection.value) return

    try {
      const answer = new RTCSessionDescription({ type: 'answer', sdp: answerSDP })
      await peerConnection.value.setRemoteDescription(answer)
      isCallActive.value = true
      isConnecting.value = false
      startAudioLevelMonitor()
    } catch (error) {
      isConnecting.value = false
      throw error
    }
  }

  const hangup = () => {
    cleanup()
    isCallActive.value = false
    isConnecting.value = false
  }

  const cleanup = () => {
    if (animationFrameId !== null) {
      cancelAnimationFrame(animationFrameId)
      animationFrameId = null
    }

    if (audioContext) {
      audioContext.close().catch(() => {})
      audioContext = null
      analyser = null
    }

    if (localStream.value) {
      localStream.value.getTracks().forEach((track) => track.stop())
      localStream.value = null
    }

    if (peerConnection.value) {
      peerConnection.value.close()
      peerConnection.value = null
    }

    audioLevel.value = 0
  }

  const startAudioLevelMonitor = () => {
    if (!localStream.value) return

    try {
      audioContext = new AudioContext()
      const source = audioContext.createMediaStreamSource(localStream.value)
      analyser = audioContext.createAnalyser()
      analyser.fftSize = 256
      source.connect(analyser)

      const dataArray = new Uint8Array(analyser.frequencyBinCount)

      const updateLevel = () => {
        if (!analyser) return
        analyser.getByteFrequencyData(dataArray)
        let sum = 0
        for (let i = 0; i < dataArray.length; i++) {
          sum += dataArray[i]
        }
        audioLevel.value = sum / dataArray.length / 255
        animationFrameId = requestAnimationFrame(updateLevel)
      }

      updateLevel()
    } catch (error) {
      console.error('启动音频监控失败:', error)
    }
  }

  onBeforeUnmount(() => {
    cleanup()
  })

  return {
    peerConnection,
    localStream,
    isCallActive,
    isConnecting,
    audioLevel,
    createOffer,
    handleAnswer,
    hangup,
  }
}
