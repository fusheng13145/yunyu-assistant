/**
 * 通话录音收尾结局判定（v2.49 · C-109）
 *
 * 后端 `POST /api/call-records/{id}/recording` 有多条真实拒绝路径（413 超上限、401 会话失效、
 * 500 落盘失败），而两处视图原先只 `uploadRecording(...).catch(console.error)` ⇒ 用户说完"挂断"
 * 就以为录音存好了，进通话记录页才发现没有音频。这里把"该说什么"收成一个纯函数：
 * 不碰 fetch、不碰通知实现，因此可被 `scripts/check-recording-upload.mjs` 直接注入假件验证。
 */

export interface RecordingOutcome {
  message: string
  level: 'warning' | 'info'
}

/** 通话记录已建立、却没拿到可上传的音频（录制器未产出 chunk 或被提前丢弃） */
export const NO_AUDIO_OUTCOME: RecordingOutcome = {
  message: '本次通话没有录到可上传的音频，该记录无法回放',
  level: 'warning',
}

/**
 * 上传失败的提示文案。`parseResponse` 只把服务端 message 放进 Error、**丢掉了 HTTP 状态码**，
 * 所以这里透传文案而不是按状态码分档（按 message 猜 413 会把"文案改了"误判成"缺陷修好了"）。
 */
export function uploadFailureOutcome(error: unknown): RecordingOutcome {
  const raw = error instanceof Error ? error.message : ''
  const reason = raw.trim() || '服务端未给出原因'
  return { message: `通话录音上传失败：${reason}`, level: 'warning' }
}

/**
 * 挂断后处理录音：有音频就上传，上传结果一律可见，且同步返回不阻塞通话收尾。
 * `callId` 为空时**不提示**——建链那一刻已经提示过"通话记录未建立，本次通话将不保存录音"，
 * 挂断再弹一次是重复告警，会让人以为是两次不同的故障。
 */
export function finishRecordingUpload(
  callId: string,
  blob: Blob | null,
  upload: (id: string, file: Blob) => Promise<unknown>,
  notify: (outcome: RecordingOutcome) => void,
): void {
  if (!callId) return
  if (!blob || blob.size === 0) {
    notify(NO_AUDIO_OUTCOME)
    return
  }
  upload(callId, blob).catch((error: unknown) => {
    console.error('上传通话录音失败', error)
    notify(uploadFailureOutcome(error))
  })
}
