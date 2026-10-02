import type { DisplayMessage } from '../types'
import type { ChatRecordMessage } from '../api/session'

/**
 * 会话历史单条记录 → 页面展示消息。
 * 逐字提取自 ChatRobot.vue 的两处同形映射（首屏加载 / 翻页加载更早），
 * 目的是让"历史回看"与"实时帧"走同一份口径，改一处不会漏另一处。
 * knowledgebase 只在助手消息上带：后端也只往助手记录写这一列。
 */

/**
 * tool_result 列是 MySQL JSON 类型：后端把结果文本折成 JSON 字符串标量入库，
 * 读取侧解析回原文；坏值（历史遗留/手工行）原样返回不炸。
 */
function parseJsonScalar(raw: string | null | undefined): string {
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw) as unknown
    return typeof parsed === 'string' ? parsed : raw
  } catch {
    return raw
  }
}

export function mapHistoryRecord(r: ChatRecordMessage): DisplayMessage | null {
  if (r.role === 0) return { role: 'user', text: r.message }
  if (r.role === 1) return { role: 'assistant', text: r.message, costTime: r.costTime, knowledgebase: r.knowledgebase }
  // 工具轨迹行（v2.72 · S-19 收口后落库）：形状与实时帧 push-tool-call/push-tool-result 落下的消息一致
  if (r.role === 2) return { role: 'tool_call', toolName: r.toolName, text: r.toolArgs || '' }
  if (r.role === 3) {
    const result = parseJsonScalar(r.toolResult)
    return { role: 'tool_result', toolName: r.toolName, toolResult: result, text: result }
  }
  return null
}
