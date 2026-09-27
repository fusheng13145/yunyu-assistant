/**
 * 通话录音上传结果可见性验证（v2.49 · C-109 的门禁侧）。
 *
 * 锁定的事实：后端 `POST /api/call-records/{id}/recording` 有 413 / 401 / 500 等真实拒绝路径，
 * 而 ChatRobot 与 SmartRobot 原先各写一份 `uploadRecording(...).catch(console.error)` ⇒
 * 用户挂断后以为录音已存，进通话记录页才发现没有音频（v2.40 收口了 39 处 HTTP 吞错，
 * 这条"挂断后台上传"是它的镜像漏点：不在任何请求链的 await 路径上，所以从未被 noticing）。
 * 现在两处共用 frontend/src/utils/recordingUpload.ts 的 finishRecordingUpload。
 * 口径与 check-chat-frame.mjs 一致：无测试框架，用 Node 的 TS 类型剥离直接 import 生产模块。
 * 运行：node scripts/check-recording-upload.mjs
 */

const MOD = new URL('../frontend/src/utils/recordingUpload.ts', import.meta.url).href
const VIEW = new URL('../frontend/src/views/SmartRobot.vue', import.meta.url)
const VIEW2 = new URL('../frontend/src/views/ChatRobot.vue', import.meta.url)
const API = new URL('../frontend/src/api/callRecord.ts', import.meta.url)

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
  check('util 不 import 任何相对模块（Node 类型擦除门禁的前提）',
    !/^import .*from ['"]\.\.?\//m.test(readFileSync(new URL(MOD), 'utf8')))
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
