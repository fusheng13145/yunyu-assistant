/**
 * 通话录音上传结果必须用户可见：413/401/500 等拒绝路径不能只 console.error。
 * 同时锁双轨混音（recordingMix）的每条回退分支与接线形态；
 * "混出来的文件里两段声音都在"由 e2e/recording-mix.spec.ts 在浏览器级取证。
 * 口径：无测试框架，Node TS 类型剥离直接 import 生产模块。
 * 运行：node scripts/frontend/check-recording-upload.mjs
 */

const MOD = new URL('../../frontend/src/utils/recordingUpload.ts', import.meta.url).href
const MIX_MOD = new URL('../../frontend/src/utils/recordingMix.ts', import.meta.url).href
const COMPOSABLE = new URL('../../frontend/src/composables/useWebRTC.ts', import.meta.url)
const VIEW = new URL('../../frontend/src/views/SmartRobot.vue', import.meta.url)
const VIEW2 = new URL('../../frontend/src/views/ChatRobot.vue', import.meta.url)
const API = new URL('../../frontend/src/api/callRecord.ts', import.meta.url)

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

const { finishRecordingUpload, uploadFailureOutcome, NO_AUDIO_OUTCOME } = await import(MOD)
const { readFileSync } = await import('node:fs')

/** 录音在 Node 里不是可构造的媒体对象，用形状相同的假件即可（被测码只看 size 与真值） */
function audio(size = 1024) {
  return size > 0 ? { size } : { size: 0 }
}

function spy() {
  const calls = []
  return { calls, notify: (outcome) => calls.push(outcome) }
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

console.log('\n[1] 上传成功/失败都要有确定结局')
{
  const s = spy()
  const uploads = []
  const ret = finishRecordingUpload('call-1', audio(), (id, file) => {
    uploads.push([id, file])
    return Promise.resolve()
  }, s.notify)
  check('有 callId 且有音频 ⇒ 恰好上传一次', uploads.length === 1 && uploads[0][0] === 'call-1')
  check('上传时带的是那份 blob（不被换成新对象）', uploads[0][1] && uploads[0][1].size === 1024)
  check('成功不弹提示（无消息可报）', s.calls.length === 0, `实际 ${s.calls.length} 条`)
  check('同步返回 undefined：挂断不等上传（本批缺陷的另一半）', ret === undefined)
}
{
  const s = spy()
  let unhandled = null
  const onUnhandled = (e) => { unhandled = e }
  process.once('unhandledRejection', onUnhandled)
  const realError = console.error
  let logged = 0
  console.error = () => { logged += 1 }
  finishRecordingUpload('call-2', audio(), () => Promise.reject(new Error('上传文件超过服务端大小限制')), s.notify)
  await flush()
  console.error = realError
  process.off('unhandledRejection', onUnhandled)
  check('失败必弹一次提示', s.calls.length === 1, `实际 ${s.calls.length} 条`)
  check('提示里带服务端的拒绝原因（用户要能看出是自己文件太大）',
    s.calls.length === 1 && s.calls[0].message.includes('超过服务端大小限制'))
  check('级别为 warning（不是 error 红条：通话本身已完成）', s.calls.length === 1 && s.calls[0].level === 'warning')
  check('reject 被就地消化，不冒泡成未处理拒绝（挂断路径上没人 catch）', unhandled === null,
    unhandled ? String(unhandled) : '')
  check('console.error 仍保留（v2.40 口径：可见提示不替换开发者日志）', logged === 1, `实际 ${logged} 次`)
}

console.log('\n[2] "没有录音"与"没建记录"要分家')
{
  const s = spy()
  const uploads = []
  finishRecordingUpload('call-3', null, () => { uploads.push(1); return Promise.resolve() }, s.notify)
  check('blob 为 null ⇒ 提示"没有录到可上传的音频"',
    s.calls.length === 1 && s.calls[0] === NO_AUDIO_OUTCOME)
  check('blob 为 null ⇒ 不发无意义的上传', uploads.length === 0)
}
{
  const s = spy()
  finishRecordingUpload('call-4', audio(0), () => Promise.resolve(), s.notify)
  check('blob 长度为 0 也算没录到（MediaRecorder 空 chunk 是真实结局）',
    s.calls.length === 1 && s.calls[0] === NO_AUDIO_OUTCOME)
}
{
  const s = spy()
  const uploads = []
  finishRecordingUpload('', audio(), () => { uploads.push(1); return Promise.resolve() }, s.notify)
  check('callId 为空 ⇒ 不上传也不提示（建链那一刻已提示过，重复告警是噪声）',
    s.calls.length === 0 && uploads.length === 0)
}

console.log('\n[3] 拒绝原因的兜底形状')
{
  const o = uploadFailureOutcome(new Error('未登录或登录已失效'))
  check('Error 文案原样进入提示', o.message.includes('未登录或登录已失效'))
  check('空文案不留空（不能让用户看见"上传失败："就完了）',
    uploadFailureOutcome(new Error('   ')).message.includes('服务端未给出原因'))
  check('非 Error 抛出物不崩（reject 一个字符串在真实代码里会发生）',
    uploadFailureOutcome('boom').message.includes('服务端未给出原因'))
  check('null/undefined 抛出物不崩',
    uploadFailureOutcome(null).level === 'warning' && uploadFailureOutcome(undefined).level === 'warning')
}

console.log('\n[4] 视图接入收口（防第三次复制粘贴，C-89/C-91 同形）')
{
  const a = readFileSync(VIEW, 'utf8')
  const b = readFileSync(VIEW2, 'utf8')
  const api = readFileSync(API, 'utf8')
  for (const [name, src] of [['SmartRobot', a], ['ChatRobot', b]]) {
    check(`${name} 经统一入口 finishRecordingUpload 收尾`,
      /from\s*'[^']*utils\/recordingUpload'/.test(src) && src.includes('finishRecordingUpload(callId, blob, uploadRecording'))
    check(`${name} 不再保留裸 console.error 的吞错上传`,
      !/uploadRecording\(callId, blob\)\.catch/.test(src))
    check(`${name} 仍保留建链时"通话记录未建立"的提示（callId 空侧不重复弹，靠它）`,
      src.includes('通话记录未建立，本次通话将不保存录音'))
    check(`${name} 仍保留建链时"录音启动失败"的提示`,
      src.includes('录音启动失败，本次通话将不保存录音'))
  }
  check('上传 API 仍以函数导出（util 不直连 fetch，保持可注入可桩测）',
    /export async function uploadRecording/.test(api))
  for (const [label, url] of [['recordingUpload', MOD], ['recordingMix', MIX_MOD]]) {
    check(`${label} 不 import 任何相对模块（Node 类型擦除门禁的前提）`,
      !/^import .*from ['"]\.\.?\//m.test(readFileSync(new URL(url), 'utf8')))
  }
}

console.log('\n[5] 混音分流：能混就合成单轨，不能混就显式回退')
{
  const { buildRecordingStream } = await import(MIX_MOD)

  const track = (id) => ({ id, kind: 'audio' })
  const streamOf = (tracks) => ({ getAudioTracks: () => tracks })
  const factory = { pack: (tracks) => streamOf(tracks), wrap: (t) => streamOf([t]) }

  /**
   * @param {{state?: string, failSource?: boolean, failDest?: boolean, destTracks?: unknown[]}} opts
   */
  function ctx(opts = {}) {
    const seen = { destinations: 0, sources: [], connected: [], destStream: null }
    const destStream = streamOf(opts.destTracks ?? [track('mixed')])
    seen.destStream = destStream
    return {
      seen,
      state: opts.state ?? 'running',
      createMediaStreamDestination: () => {
        seen.destinations += 1
        if (opts.failDest) throw new Error('不支持 createMediaStreamDestination')
        return { stream: destStream }
      },
      createMediaStreamSource: (s) => {
        seen.sources.push(s.getAudioTracks()[0].id)
        if (opts.failSource) throw new Error('不支持 createMediaStreamSource')
        return { connect: () => seen.connected.push(true) }
      },
    }
  }

  const mic = track('mic')
  const ai = track('ai')

  // 1) 双轨 + running ⇒ 合成单轨，且两条轨都真的连进了 destination
  const ok = ctx()
  const mixedOut = buildRecordingStream([mic, ai], ok, factory)
  check('双轨且上下文 running ⇒ mixed=true', mixedOut.mixed === true && mixedOut.reason === 'mixed')
  check('交回的是合成流，不是原始多轨流（MediaRecorder 只看到一条轨）',
    mixedOut.stream === ok.seen.destStream && mixedOut.stream.getAudioTracks().length === 1)
  check('麦克风轨与远端 AI 轨都送进了混音（只连一条就是"录得到自己听不到 AI"）',
    ok.seen.sources.length === 2 && ok.seen.sources.includes('mic') && ok.seen.sources.includes('ai'),
    `实际 ${JSON.stringify(ok.seen.sources)}`)
  check('两条源都 connect 到 destination', ok.seen.connected.length === 2)

  // 2) 回退分支：每一条都必须给出原因，且回退流内容保真（就是改动前的行为）
  const single = buildRecordingStream([mic], ctx(), factory)
  check('单轨 ⇒ 不混音（没有"编进哪条"的歧义），原因显式',
    single.mixed === false && single.reason === 'single-track')
  check('单轨 ⇒ 交回的是原轨打包流', single.stream.getAudioTracks()[0] === mic)

  const emptyList = buildRecordingStream([], ctx(), factory)
  check('空轨列表不抛（调用方已挡，这里只锁"模块自己也不崩"）',
    emptyList.mixed === false && emptyList.stream.getAudioTracks().length === 0)

  const noCtx = ctx()
  const noCtxOut = buildRecordingStream([mic, ai], null, factory)
  check('无 AudioContext ⇒ 回退多轨并给出原因',
    noCtxOut.mixed === false && noCtxOut.reason === 'no-context')
  check('无 AudioContext ⇒ 不去创建 destination（回退路径零副作用）',
    noCtx.seen.destinations === 0)

  const suspended = ctx({ state: 'suspended' })
  const suspendedOut = buildRecordingStream([mic, ai], suspended, factory)
  check('上下文非 running ⇒ 回退（混出来会是静音文件，比不混更糟）',
    suspendedOut.mixed === false && suspendedOut.reason === 'context-not-running')
  check('非 running ⇒ 不建 destination', suspended.seen.destinations === 0)
  check('非 running ⇒ 回退流仍是两条原轨（不丢 AI 音轨）',
    suspendedOut.stream.getAudioTracks().length === 2)

  const failDest = ctx({ failDest: true })
  const failDestOut = buildRecordingStream([mic, ai], failDest, factory)
  check('WebAudio 建目的端抛错 ⇒ 回退而不是录音失败（通话录音仍可用）',
    failDestOut.mixed === false && failDestOut.reason === 'mix-failed'
      && failDestOut.stream.getAudioTracks().length === 2)

  const failSource = ctx({ failSource: true })
  const failSourceOut = buildRecordingStream([mic, ai], failSource, factory)
  check('WebAudio 建源抛错 ⇒ 同样回退，异常不外冒',
    failSourceOut.reason === 'mix-failed' && failSourceOut.stream.getAudioTracks().length === 2)

  const emptyDest = ctx({ destTracks: [] })
  const emptyDestOut = buildRecordingStream([mic, ai], emptyDest, factory)
  check('合成流里没有音轨 ⇒ 判定为回退（不能把空流交给 MediaRecorder 当成混音成功）',
    emptyDestOut.mixed === false && emptyDestOut.reason === 'empty-destination')

  // 鉴别力：把"只连第一条轨"的退化实现喂给同一套桩，第 4 条断言必须落空
  const half = ctx()
  const realSource = half.createMediaStreamSource
  half.createMediaStreamSource = (s) => {
    const node = realSource(s)
    return half.seen.connected.length > 0 ? { connect: () => {} } : node
  }
  const halfOut = buildRecordingStream([mic, ai], half, factory)
  check('反向锚点：漏连第二条轨的退化实现 ⇒ sources 记 2 而 connect 只有 1（证明上面的 connect 判据有鉴别力）',
    halfOut.mixed === true && half.seen.sources.length === 2 && half.seen.connected.length === 1,
    `实际 sources=${half.seen.sources.length} connected=${half.seen.connected.length}`)
}

console.log('\n[6] 混音接线收口（防实现退回 composable 内部）')
{
  const src = readFileSync(COMPOSABLE, 'utf8')
  check('useWebRTC 经统一入口 buildRecordingStream 取流',
    src.includes("from '../utils/recordingMix'") && src.includes('buildRecordingStream(tracks, audioContext,'))
  check('MediaRecorder 的入参是分流结果（不再各写一份 new MediaStream(tracks)）',
    /new MediaRecorder\(source\.stream,/.test(src) && !/new MediaRecorder\(recordStream/.test(src))
  check('startRecording 仍是同步返回 boolean（改成 Promise 会让 `!webrtc.startRecording()` 恒真放行）',
    /const startRecording = \(\): boolean =>/.test(src))
  check('混音回退留痕（mix-failed / empty-destination 要能在控制台看见）',
    /console\.warn\('通话录音混音回退:'/.test(src)
      && src.includes("source.reason === 'mix-failed'")
      && src.includes("source.reason === 'empty-destination'"))
  check('音频监控在非 running 时唤醒上下文（录音分流只认 running）',
    /if \(audioContext\.state !== 'running'\) \{\s*\n\s*audioContext\.resume\(\)/.test(src))
  for (const [name, file] of [['SmartRobot', VIEW], ['ChatRobot', VIEW2]]) {
    check(`${name} 仍按同步 boolean 处理录音启动失败`,
      readFileSync(file, 'utf8').includes('else if (!webrtc.startRecording()) {'))
  }
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
