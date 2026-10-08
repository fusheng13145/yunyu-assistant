/**
 * 通话录音取流的分流决策（v2.87 · C-165）。
 *
 * 双轨（本方麦克风 + 远端 AI）直接交给 MediaRecorder 时，浏览器编进去几条轨不由我们决定，
 * 于是"回放文件里两段声音是否都在"既不能证实也不能证伪（手册 6.6 的 C-82 边界）。这里把决策
 * 收成纯函数：能混就合成单轨，不能混就**显式**回退并交出原因——回退不是失败，但必须是
 * "知道自己正在回退到旧行为"，而不是静默产出一份可能只有一半声音的文件。
 *
 * pack/wrap 由调用方注入：Node 桩测环境里没有 MediaStream 构造器，而门禁必须能跑这几条分支。
 */

export type MixReason =
  | 'mixed'
  | 'single-track'
  | 'no-context'
  | 'context-not-running'
  | 'mix-failed'
  | 'empty-destination'

export interface RecordingSource {
  readonly stream: MediaStream
  readonly mixed: boolean
  readonly reason: MixReason
}

export interface StreamFactory {
  pack: (tracks: MediaStreamTrack[]) => MediaStream
  wrap: (track: MediaStreamTrack) => MediaStream
}

/** 需要混音的最少音轨数：单轨没有"编进哪一条"的歧义 */
const MIX_MIN_TRACKS = 2

export function buildRecordingStream(
  tracks: MediaStreamTrack[],
  ctx: AudioContext | null,
  factory: StreamFactory,
): RecordingSource {
  if (tracks.length < MIX_MIN_TRACKS) {
    return { stream: factory.pack(tracks), mixed: false, reason: 'single-track' }
  }
  if (!ctx) {
    return { stream: factory.pack(tracks), mixed: false, reason: 'no-context' }
  }
  // 非 running 的 AudioContext 不产声，混出来会是一份静音文件——宁可不混
  if (ctx.state !== 'running') {
    return { stream: factory.pack(tracks), mixed: false, reason: 'context-not-running' }
  }
  try {
    const destination = ctx.createMediaStreamDestination()
    for (const track of tracks) {
      ctx.createMediaStreamSource(factory.wrap(track)).connect(destination)
    }
    if (destination.stream.getAudioTracks().length === 0) {
      return { stream: factory.pack(tracks), mixed: false, reason: 'empty-destination' }
    }
    return { stream: destination.stream, mixed: true, reason: 'mixed' }
  } catch {
    return { stream: factory.pack(tracks), mixed: false, reason: 'mix-failed' }
  }
}
