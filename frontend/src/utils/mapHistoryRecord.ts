import type { DisplayMessage } from '../types'
import type { ChatRecordMessage } from '../api/session'

/**
 * 会话历史单条记录 → 页面展示消息。
 * 逐字提取自 ChatRobot.vue 的两处同形映射（首屏加载 / 翻页加载更早），
 * 目的是让"历史回看"与"实时帧"走同一份口径，改一处不会漏另一处。
 * knowledgebase 只在助手消息上带：后端也只往助手记录写这一列。
 */
export function mapHistoryRecord(r: ChatRecordMessage): DisplayMessage | null {
  if (r.role === 0) return { role: 'user', text: r.message }
  if (r.role === 1) return { role: 'assistant', text: r.message, costTime: r.costTime, knowledgebase: r.knowledgebase }
  if (r.role === 2) return { role: 'tool_call', toolName: r.toolName, text: r.toolArgs || r.message }
  if (r.role === 3) return { role: 'tool_result', toolName: r.toolName, toolResult: r.toolResult || r.message, text: r.toolResult || r.message }
  return null
}
