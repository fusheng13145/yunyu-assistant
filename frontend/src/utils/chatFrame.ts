import type { DisplayMessage, KnowledgebaseInfo, TokenUsage } from '../types'

/**
 * 聊天/语音 WebSocket 帧的统一收口（v2.43）。
 * 后端拒绝类错误（消息过长、配额耗尽）以 {type:'error', data:'文案'} 帧下发且不会断链，
 * 此前两个视图的内联分派都没有消费该帧，isTyping 永不复位 ⇒ 输入框冻结到刷新。
 */

export interface ChatStreamState {
  /** 是否有在途回复（前端 UI 口径：true 即输入框锁定） */
  typing: boolean
  /** 是否下一条 assistant_message 是本轮流式首段 */
  firstOfStream: boolean
}

export type ChatFrameInstruction =
  | { kind: 'begin-stream'; segment: string }
  | { kind: 'append-stream'; segment: string }
  | { kind: 'complete-message'; text: string; costTime?: number; knowledgebase?: KnowledgebaseInfo; tokenUsage?: TokenUsage }
  | { kind: 'show-error'; message: string }
  | { kind: 'push-tool-call'; toolName?: string; text: string }
  | { kind: 'push-tool-result'; toolName?: string; toolResult?: string; text: string }

export function initialChatStreamState(): ChatStreamState {
  return { typing: false, firstOfStream: true }
}

/** 回合类帧（含 error 回执）与心跳/语音专属帧的分界：前者一律由本模块收口 */
const CHAT_TURN_FRAME_TYPES = new Set(['assistant_message', 'query_end', 'error', 'tool_call', 'tool_result'])

function frameTypeOf(rawEvent: unknown): unknown {
  const raw = (rawEvent as { data?: unknown } | null)?.data
  try {
    return (JSON.parse(typeof raw === 'string' ? raw : '') as { type?: unknown })?.type
  } catch {
    return undefined
  }
}

export function isChatTurnFrame(rawEvent: unknown, preparsed?: unknown): boolean {
  const type = preparsed === undefined ? frameTypeOf(rawEvent) : (preparsed as { type?: unknown })?.type
  return typeof type === 'string' && CHAT_TURN_FRAME_TYPES.has(type)
}

function normalizeKnowledgebase(raw: unknown): KnowledgebaseInfo | undefined {
  if (typeof raw === 'string') return raw ? { docName: [raw] } : undefined
  if (raw && typeof raw === 'object') return raw as KnowledgebaseInfo
  return undefined
}

/**
 * 把一帧 WS 事件翻译成「视图指令 + 新状态」。
 * 只认聊天通道的四种回合帧 + error 回执；语音/心跳等其它帧一律原样带回状态，
 * 语音通道复用本函数只为统一 error 帧的解冻出口，其专属帧的分派仍留在视图内。
 */
export function describeChatFrame(rawEvent: unknown, state: ChatStreamState, preparsed?: unknown): { instruction: ChatFrameInstruction | null; state: ChatStreamState } {
  const raw = (rawEvent as { data?: unknown } | null)?.data
  let frame: unknown
  try {
    frame = preparsed ?? JSON.parse(typeof raw === 'string' ? raw : '')
  } catch {
    // 解析失败同样必须给出出口：作废在途回合并提示，不再只留 console.error
    return { instruction: { kind: 'show-error', message: '收到无法解析的消息，本条回复可能不完整' }, state: { typing: false, firstOfStream: true } }
  }
  const type = (frame as { type?: unknown } | null)?.type
  const data = (frame as { data?: unknown } | null)?.data
  switch (type) {
    case 'assistant_message': {
      const answer = (data ?? {}) as { streamEnd?: boolean; segment?: string }
      if (answer.streamEnd) return { instruction: null, state: { typing: false, firstOfStream: true } }
      const segment = answer.segment ?? ''
      return state.firstOfStream
        ? { instruction: { kind: 'begin-stream', segment }, state: { typing: state.typing, firstOfStream: false } }
        : { instruction: { kind: 'append-stream', segment }, state }
    }
    case 'query_end': {
      const q = (data ?? {}) as Record<string, unknown>
      return {
        instruction: {
          kind: 'complete-message',
          text: typeof q.message === 'string' ? q.message : '',
          costTime: q.costTime as number | undefined,
          knowledgebase: normalizeKnowledgebase(q.knowledgebase),
          tokenUsage: (q.tokenUsage ?? undefined) as TokenUsage | undefined,
        },
        state: { typing: false, firstOfStream: true },
      }
    }
    case 'error':
      return {
        instruction: { kind: 'show-error', message: data == null ? '服务端拒绝了本条消息' : String(data) },
        state: { typing: false, firstOfStream: true },
      }
    case 'tool_call': {
      const f = frame as { toolName?: string; toolArgs?: string }
      // 工具帧隔断了在途流式气泡：firstOfStream 复位，续答正文开新气泡而不是被 append 丢掉
      return { instruction: { kind: 'push-tool-call', toolName: f.toolName, text: f.toolArgs || '' }, state: { typing: state.typing, firstOfStream: true } }
    }
    case 'tool_result': {
      const tr = (data ?? {}) as { name?: string; result?: string }
      return { instruction: { kind: 'push-tool-result', toolName: tr?.name, toolResult: tr?.result, text: tr?.result || '' }, state: { typing: state.typing, firstOfStream: true } }
    }
    default:
      return { instruction: null, state }
  }
}

/**
 * 把指令落到消息数组上（消息形状在两视图同为 DisplayMessage[]，实现只留一份）。
 * 回合结束时没有位在途流式气泡（如已切会话）则不动数组。
 */
export function applyChatFrameInstruction(messages: DisplayMessage[], instruction: ChatFrameInstruction): void {
  switch (instruction.kind) {
    case 'begin-stream':
      messages.push({ role: 'assistant', text: instruction.segment, isStreaming: true })
      break
    case 'append-stream': {
      const last = messages[messages.length - 1]
      if (last?.role === 'assistant') {
        last.text += instruction.segment
      } else {
        // 上一段正文之后插过工具卡片（或流式气泡已不在位）：开新气泡，不把正文静默丢掉
        messages.push({ role: 'assistant', text: instruction.segment, isStreaming: true })
      }
      break
    }
    case 'complete-message': {
      const last = messages[messages.length - 1]
      if (last?.role === 'assistant' && last.isStreaming) {
        last.text = instruction.text || last.text
        last.isStreaming = false
        last.costTime = instruction.costTime
        if (instruction.knowledgebase) last.knowledgebase = instruction.knowledgebase
        if (instruction.tokenUsage) last.tokenUsage = instruction.tokenUsage
      }
      break
    }
    case 'push-tool-call':
      messages.push({ role: 'tool_call', toolName: instruction.toolName, text: instruction.text })
      break
    case 'push-tool-result':
      messages.push({ role: 'tool_result', toolName: instruction.toolName, toolResult: instruction.toolResult, text: instruction.text })
      break
    case 'show-error':
      break
  }
}

/**
 * 聊天/语音两条 WS 通道共用的回合处理入口：视图专属帧（webrtc/asr/hangup 等）返回 false，
 * 由调用方自行分派；回合帧返回 true，解冻、数组落笔、错误提示在此一次完成。
 */
export function handleChatTurnFrame(
  rawEvent: unknown,
  stateRef: { value: ChatStreamState },
  messages: DisplayMessage[],
  showError: (message: string) => void,
  preparsed?: unknown,
): { handled: boolean; queryEnded: boolean } {
  if (!isChatTurnFrame(rawEvent, preparsed)) return { handled: false, queryEnded: false }
  const { instruction, state } = describeChatFrame(rawEvent, stateRef.value, preparsed)
  stateRef.value = state
  if (instruction) {
    if (instruction.kind === 'show-error') showError(instruction.message)
    else applyChatFrameInstruction(messages, instruction)
  }
  return { handled: true, queryEnded: instruction?.kind === 'complete-message' }
}
