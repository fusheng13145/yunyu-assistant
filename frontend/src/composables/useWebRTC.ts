import { ref, onBeforeUnmount } from 'vue'
import { request } from '../api/auth'

/** 从后端 /api/webrtc/config 拉取 ICE 服务器配置（STUN/TURN），生产可配置 TURN 穿透对称 NAT */
async function fetchIceServers(): Promise<RTCIceServer[]> {
  try {
    const data = await request<{ iceServers?: RTCIceServer[] }>('/api/webrtc/config')
    return Array.isArray(data?.iceServers) ? data.iceServers : []
  } catch {
    return []
  }
}

/** 未配置时的默认 STUN（Google 公共 STUN） */
const DEFAULT_ICE_SERVERS: RTCIceServer[] = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
]

export function useWebRTC() {
  const peerConnection = ref<RTCPeerConnection | null>(null)
  const localStream = ref<MediaStream | null>(null)
  /** 远端音轨（AI 声音），用于合成录音 */
  const remoteStream = ref<MediaStream | null>(null)
  const isCallActive = ref(false)
  const isConnecting = ref(false)
  const audioLevel = ref(0)
  /** 当前 WebRTC 连接状态（weak 网络降级监听） */
  const connectionState = ref<RTCPeerConnectionState>('new')
  /** 是否正在录音 */
  const isRecording = ref(false)

  let audioContext: AudioContext | null = null
  let analyser: AnalyserNode | null = null
  let animationFrameId: number | null = null
  let recorder: MediaRecorder | null = null
  let recordingChunks: Blob[] = []

  /** 缓存拉取到的 ICE 配置（避免每次建链重复请求） */
  let cachedIceServers: RTCIceServer[] | null = null

  const createOffer = async (): Promise<string> => {
    // 从后端下发配置，失败/为空时回退默认 STUN
    if (cachedIceServers === null) {
      const servers = await fetchIceServers()
      cachedIceServers = servers.length > 0 ? servers : DEFAULT_ICE_SERVERS
    }
    peerConnection.value = new RTCPeerConnection({ iceServers: cachedIceServers })
    isConnecting.value = true

    // 收集远端音轨（对端 AI 声音），供通话录音合成
    peerConnection.value.ontrack = (event) => {
      const tracks = event.streams.length > 0 ? event.streams[0].getAudioTracks() : [event.track]
      if (!remoteStream.value) {
        remoteStream.value = new MediaStream()
      }
      tracks.forEach((track) => {
        if (!remoteStream.value!.getTracks().includes(track)) {
          remoteStream.value!.addTrack(track)
        }
      })
    }

    // 弱网降级：连接状态变化对外暴露，failed 时由上层降级处理
    peerConnection.value.onconnectionstatechange = () => {
      connectionState.value = peerConnection.value?.connectionState ?? 'closed'
    }

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

  /**
   * 开始录制通话音频（本方麦克风 + 对端音轨合成，webm/opus）
   * @returns 是否成功开始
   */
  const startRecording = (): boolean => {
    if (recorder || !peerConnection.value) return false
    const tracks: MediaStreamTrack[] = []
    if (localStream.value) tracks.push(...localStream.value.getAudioTracks())
    if (remoteStream.value) tracks.push(...remoteStream.value.getAudioTracks())
    if (tracks.length === 0) return false
    try {
      const mixStream = new MediaStream(tracks)
      const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : 'audio/webm'
      recorder = new MediaRecorder(mixStream, { mimeType: mime })
      recordingChunks = []
      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) recordingChunks.push(event.data)
      }
      recorder.start(1000)
      isRecording.value = true
      return true
    } catch {
      recorder = null
      recordingChunks = []
      return false
    }
  }

  /**
   * 停止录音并返回录音 blob
   * @returns 录音 blob（无可录内容或未在录音时返回 null）
   */
  const stopRecording = (): Promise<Blob | null> => {
    return new Promise((resolve) => {
      if (!recorder) {
        resolve(null)
        return
      }
      const target = recorder
      target.onstop = () => {
        recorder = null
        isRecording.value = false
        const blob = recordingChunks.length > 0
          ? new Blob(recordingChunks, { type: target.mimeType || 'audio/webm' })
          : null
        recordingChunks = []
        resolve(blob)
      }
      target.stop()
    })
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

    // 停止录音（若仍在录制则丢弃未保存的数据）
    if (recorder && recorder.state !== 'inactive') {
      recorder.onstop = null
      recorder.stop()
    }
    recorder = null
    recordingChunks = []
    isRecording.value = false

    if (localStream.value) {
      localStream.value.getTracks().forEach((track) => track.stop())
      localStream.value = null
    }

    // 远端音轨随连接关闭释放
    remoteStream.value = null

    if (peerConnection.value) {
      peerConnection.value.close()
      peerConnection.value = null
    }

    connectionState.value = 'new'
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
    remoteStream,
    isCallActive,
    isConnecting,
    audioLevel,
    connectionState,
    isRecording,
    createOffer,
    handleAnswer,
    hangup,
    startRecording,
    stopRecording,
  }
}
