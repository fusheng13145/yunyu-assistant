import type { DisplayMessage } from '../types'

/**
 * 转义 Markdown 特殊字符，避免解析异常
 */
function escapeMarkdown(text: string): string {
  return text
    .replace(/\\/g, '\\\\')
    .replace(/([*_`#\[\]()])/g, '\\$1')
}

/**
 * 生成格式化的时间戳（用于文件名和导出信息）
 */
function formatTimestamp(): string {
  const now = new Date()
  const pad = (n: number) => n.toString().padStart(2, '0')
  const y = now.getFullYear()
  const m = pad(now.getMonth() + 1)
  const d = pad(now.getDate())
  const h = pad(now.getHours())
  const min = pad(now.getMinutes())
  const s = pad(now.getSeconds())
  return `${y}-${m}-${d} ${h}:${min}:${s}`
}

/**
 * 下载文件通用方法
 */
function downloadFile(content: string, filename: string, mimeType: string): void {
  const blob = new Blob([content], { type: mimeType })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

/**
 * 导出对话为 Markdown 文件
 */
export function exportChatToMarkdown(messages: DisplayMessage[], assistantName: string): void {
  const safeName = escapeMarkdown(assistantName)
  const timestamp = formatTimestamp()
  const filenameTimestamp = timestamp.replace(/[:/]/g, '-')
  let md = `# 对话记录 - ${safeName}\n\n`
  md += `> 导出时间：${timestamp}\n`
  md += `> 共 ${messages.length} 条消息\n\n---\n\n`

  for (const msg of messages) {
    switch (msg.role) {
      case 'user':
        md += `## 用户\n\n${escapeMarkdown(msg.text)}\n\n`
        break
      case 'assistant':
        md += `## ${safeName}\n\n${escapeMarkdown(msg.text)}\n\n`
        if (msg.costTime) {
          md += `*耗时 ${(msg.costTime / 1000).toFixed(2)}s*\n\n`
        }
        break
      case 'tool_call':
        md += `## 调用工具\n\n${escapeMarkdown(msg.toolName || msg.text)}\n\n`
        break
      case 'tool_result':
        const label = msg.toolName ? ` (${escapeMarkdown(msg.toolName)})` : ''
        const codeBlock = `\n\`\`\`\n${escapeMarkdown(msg.toolResult || msg.text)}\n\`\`\`\n\n`
        md += `## 工具执行结果${label}\n\n${codeBlock}`
        break
    }
    md += '---\n\n'
  }

  downloadFile(md, `${assistantName}_对话记录_${filenameTimestamp}.md`, 'text/markdown;charset=utf-8')
}

/**
 * 导出对话为 JSON 文件
 */
export function exportChatToJson(messages: DisplayMessage[], assistantName: string): void {
  const data = {
    assistantName,
    exportedAt: formatTimestamp(),
    messageCount: messages.length,
    messages: messages.map(msg => ({
      role: msg.role,
      text: msg.text,
      toolName: msg.toolName ?? null,
      toolResult: msg.toolResult ?? null,
      costTime: msg.costTime ?? null,
      isStreaming: msg.isStreaming ?? false,
    })),
  }

  const timestamp = formatTimestamp().replace(/[:/]/g, '-')
  downloadFile(
    JSON.stringify(data, null, 2),
    `${assistantName}_对话记录_${timestamp}.json`,
    'application/json;charset=utf-8'
  )
}