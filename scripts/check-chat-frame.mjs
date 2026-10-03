/**
 * 聊天 WS 帧收口验证（v2.43 C-96 的门禁侧）。
 *
 * 锁定的事实：后端 ChatWebSocketHandler 的拒绝类回执走 {type:'error', data:'文案'} 帧
 * 且不断链（配额耗尽 / 消息过长两条路径），前端必须在同一帧里完成"提示 + 解冻"，
 * 否则用户只能刷新页面。此前 SmartRobot/ChatRobot 各有一份内联分派且都缺 error 分支
 * （v2.40 收口了 HTTP 侧 39 处吞错，WS 帧这条路是镜像漏点）。
 * 口径与 check-auth-session.mjs / check-knowledgebase-flag.mjs 一致：
 * 无测试框架，用 Node 的 TS 类型剥离直接 import 生产模块。
 * 运行：node scripts/check-chat-frame.mjs
 */

const MOD = new URL('../frontend/src/utils/chatFrame.ts', import.meta.url).href
const VIEW = new URL('../frontend/src/views/SmartRobot.vue', import.meta.url)
const VIEW2 = new URL('../frontend/src/views/ChatRobot.vue', import.meta.url)

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

function frame(obj) {
  return { data: JSON.stringify(obj) }
}

const { describeChatFrame, initialChatStreamState } = await import(MOD)
const { readFileSync } = await import('node:fs')

console.log('\n[1] error 帧必须同时给出"提示"与"解冻"（本批缺陷的正身）')
{
  const r = describeChatFrame(frame({ type: 'error', data: '今日消息配额已用完' }), { typing: true, firstOfStream: false })
  check('error 帧产出 show-error 指令', r.instruction?.kind === 'show-error', `实际 ${r.instruction?.kind}`)
  check('error 文案原样透传（用户要看见后端给的拒绝理由）',
    r.instruction?.kind === 'show-error' && r.instruction.message === '今日消息配额已用完')
  check('error 帧后 typing=false（输入框解冻）', r.state.typing === false)
  check('error 帧后 firstOfStream=true（在途流式气泡作废，下轮重开）',
    r.state.firstOfStream === true)
}

console.log('\n[2] 非 error 帧不得顺手解冻（解锁只属于回合终点）')
{
  const r = describeChatFrame(frame({ type: 'error', data: 'x' }), { typing: false, firstOfStream: true })
  check('空闲期收到 error 也照常提示（配额帧可能晚于本地复位到达）',
    r.instruction?.kind === 'show-error')
  const tc = describeChatFrame(frame({ type: 'tool_call', toolName: 'web_search', toolArgs: '{"q":"a"}' }), { typing: true, firstOfStream: true })
  check('tool_call 帧 → push-tool-call 且仍锁定', tc.instruction?.kind === 'push-tool-call' && tc.state.typing === true)
  const tc2 = describeChatFrame(frame({ type: 'tool_call', toolName: 'web_search' }), { typing: true, firstOfStream: true })
  check('tool_call 参数缺省回空串（渲染层不碰 undefined）',
    tc2.instruction?.kind === 'push-tool-call' && tc2.instruction.text === '')
  const tr = describeChatFrame(frame({ type: 'tool_result', data: { name: 'web_search', result: 'ok' } }), { typing: true, firstOfStream: true })
  check('tool_result 帧 → push-tool-result', tr.instruction?.kind === 'push-tool-result' && tr.instruction.toolName === 'web_search')
  check('tool_result 缺 data 不炸（null 安全）',
    describeChatFrame(frame({ type: 'tool_result' }), { typing: true, firstOfStream: true }).instruction?.kind === 'push-tool-result')
  // v2.72 · C-141：工具帧隔断了在途流式气泡，firstOfStream 必须复位——否则续答正文以 append 落笔时
  // 找不到 assistant 气泡，被整段静默丢掉（该路径在框架内执行时代从未真实跑过）。
  // 输入必须取回合中态（firstOfStream=false）：拿 true 做输入的话，"复位"与"原样"不可分辨，变异会空转判绿
  const tcMid = describeChatFrame(frame({ type: 'tool_call', toolName: 'w' }), { typing: true, firstOfStream: false })
  const trMid = describeChatFrame(frame({ type: 'tool_result', data: { name: 'w' } }), { typing: true, firstOfStream: false })
  check('回合中态收到 tool_call/tool_result 复位 firstOfStream（续答正文要开新气泡）',
    tcMid.state.firstOfStream === true && trMid.state.firstOfStream === true)
}

console.log('\n[2.5] 工具卡之后的正文必须有落笔之处（v2.72 工具回合实证）')
{
  const { applyChatFrameInstruction } = await import(MOD)
  const msgs = []
  applyChatFrameInstruction(msgs, { kind: 'begin-stream', segment: '让我查一下' })
  applyChatFrameInstruction(msgs, { kind: 'push-tool-call', toolName: 'web_search', text: '{"q":"a"}' })
  applyChatFrameInstruction(msgs, { kind: 'push-tool-result', toolName: 'web_search', toolResult: 'ok', text: 'ok' })
  applyChatFrameInstruction(msgs, { kind: 'append-stream', segment: '查到了' })
  check('工具卡之后的 append-stream 开新气泡而不是丢文本',
    msgs.length === 4 && msgs[3].role === 'assistant' && msgs[3].text === '查到了',
    `实际 ${JSON.stringify(msgs.map((m) => m.role))}`)
  check('工具卡之前与之后的正文都在', msgs[0].text === '让我查一下' && msgs[0].role === 'assistant')
}

console.log('\n[3] 流式生命周期：解锁的两条合法路径')
{
  const s0 = { typing: true, firstOfStream: true }
  const a1 = describeChatFrame(frame({ type: 'assistant_message', data: { streamEnd: false, segment: '你' } }), s0)
  check('首段 → begin-stream 且保持锁定', a1.instruction?.kind === 'begin-stream' && a1.state.typing === true)
  check('首段之后 firstOfStream 翻转（第二段是 append 不是重开）',
    a1.state.firstOfStream === false)
  const a2 = describeChatFrame(frame({ type: 'assistant_message', data: { streamEnd: false, segment: '好' } }), a1.state)
  check('第二段 → append-stream', a2.instruction?.kind === 'append-stream')
  const a3 = describeChatFrame(frame({ type: 'assistant_message', data: { streamEnd: true, segment: '' } }), a2.state)
  check('streamEnd 段 → 不产出指令但解冻复位', a3.instruction === null && a3.state.typing === false && a3.state.firstOfStream === true)
  const q = describeChatFrame(frame({
    type: 'query_end',
    data: { message: '你好', costTime: 1234, knowledgebase: { docCount: 2, docName: ['a.pdf', 'b.pdf'] }, tokenUsage: { promptTokens: 10, completionTokens: 5 } },
  }), { typing: true, firstOfStream: false })
  check('query_end → complete-message 带全量字段（角标/耗时/token 用量不丢）',
    q.instruction?.kind === 'complete-message' && q.instruction.costTime === 1234
    && q.instruction.knowledgebase?.docCount === 2 && q.instruction.tokenUsage?.completionTokens === 5)
  check('query_end 解冻复位', q.state.typing === false && q.state.firstOfStream === true)
  const qs = describeChatFrame(frame({ type: 'query_end', data: { message: '答', costTime: 1, knowledgebase: '手册.pdf' } }), { typing: true, firstOfStream: false })
  check('knowledgebase 字符串旧形状 → 归一为 {docName:[s]}（两视图统一依赖对象）',
    qs.instruction?.kind === 'complete-message'
    && JSON.stringify(qs.instruction.knowledgebase) === JSON.stringify({ docName: ['手册.pdf'] }))
  check('query_end 缺 data 不抛异常',
    describeChatFrame(frame({ type: 'query_end' }), s0).instruction?.kind === 'complete-message')
}

console.log('\n[4] 忽略与作废帧：状态必须原样带回（回归零风险面）')
{
  const s = { typing: true, firstOfStream: false }
  for (const t of ['ping', 'pong', 'auth_result', 'webrtc_answer', 'webrtc_connected', 'asr_delta', 'hangup', '什么也没见过']) {
    const r = describeChatFrame(frame({ type: t, data: {} }), s)
    check(`${t} → 无指令且状态原样`, r.instruction === null && r.state.typing === true && r.state.firstOfStream === false)
  }
  const bad = describeChatFrame({ data: 'not json at all' }, s)
  check('非 JSON 帧 → 按 show-error 作废在途回合（不再静默 console 后冻结）',
    bad.instruction?.kind === 'show-error' && bad.state.typing === false && bad.state.firstOfStream === true)
  const empty = describeChatFrame({ data: '' }, s)
  check('空帧同样作废', empty.instruction?.kind === 'show-error')
  check('非 error 文案不得误判为 show-error 指令帧（type 判定精确）',
    describeChatFrame(frame({ type: 'errorish' }), s).instruction === null)
}

console.log('\n[5] 视图接入收口（防第三次复制粘贴，C-89/C-91 同形）')
{
  const a = readFileSync(VIEW, 'utf8')
  const b = readFileSync(VIEW2, 'utf8')
  check('SmartRobot 经统一入口 handleChatTurnFrame 消费帧', /from\s*'[^']*utils\/chatFrame'/.test(a) && a.includes('handleChatTurnFrame(event'))
  check('ChatRobot 经统一入口 handleChatTurnFrame 消费帧', /from\s*'[^']*utils\/chatFrame'/.test(b) && b.includes('handleChatTurnFrame(event'))
  check('SmartRobot 不再保留内联 query_end 分派', !/data\.type === 'query_end'/.test(a))
  check('ChatRobot 不再保留内联 query_end 分派', !/data\.type === 'query_end'/.test(b))
  check('两视图不再自带 isTyping/isFirstOfStream 平行状态（解冻只经统一入口）',
    !/const isTyping = ref/.test(a) && !/const isFirstOfStream = ref/.test(a)
    && !/const isTyping = ref/.test(b) && !/const isFirstOfStream = ref/.test(b))
  check('initialChatStreamState 由模块提供（视图不再各写一份初值）',
    typeof initialChatStreamState === 'function' && initialChatStreamState().typing === false)
}

console.log('\n[6] WS 未就绪的发送必须排队而不是丢（v2.75 · C-144，E2E 守卫首跑抓到）')
{
  const WS_MOD = new URL('../frontend/src/utils/websocket.ts', import.meta.url).href
  const wsSrc = readFileSync(new URL(WS_MOD), 'utf8').replace(/\r/g, '')
  // 负向判据只扫 send 函数体：flushPendingSends 里同形的守卫 return 是合法 no-op，不该被咬
  const sendFn = wsSrc.slice(wsSrc.indexOf('const send ='), wsSrc.indexOf('const close ='))
  check('send 在未 OPEN 时不再裸 return 丢帧（裸丢＝选完助手立刻说话会蒸发并锁死打字态）',
    /pendingSends\.push\(payload\)/.test(sendFn) && !/readyState !== WebSocket\.OPEN\) return/.test(sendFn))
  check('握手完成后按序补发，且认证帧仍在业务消息之前',
    /flushPendingSends\(\)/.test(wsSrc)
      && wsSrc.indexOf("type: 'auth'") !== -1
      && wsSrc.indexOf('flushPendingSends()') > wsSrc.indexOf("type: 'auth'"))
  check('待发队列有上限（连接已坏时排队只是拖延暴露）', /MAX_PENDING_SENDS/.test(wsSrc))
}
console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
