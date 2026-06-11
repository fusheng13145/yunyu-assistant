import type { DisplayMessage } from '../types'

function escapeMarkdown(text: string): string {
  return text
    .replace(/\\/g, '\\\\')
    .replace(/\*/g, '\\*')
    .replace(/_/g, '\\_')
    .replace(/`/g, '\\`')
    .replace(/#/g, '\\#')
}

function formatTimestamp(): string {
  const now = new Date()
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

export function exportChatToMarkdown(messages: DisplayMessage[], assistantName: string): void {
  let mdContent = `# 对话记录 - ${escapeMarkdown(assistantName)}\n\n`
  mdContent += `> 导出时间：${formatTimestamp()}\n`
  mdContent += `> 共 ${messages.length} 条消息\n\n---\n\n`

  for (const msg of messages) {
    if (msg.role === 'user') {
      mdContent += `## 👤 用户\n\n${escapeMarkdown(msg.text)}\n\n`
    } else if (msg.role === 'assistant') {
      mdContent += `## 🤖 ${escapeMarkdown(assistantName)}\n\n${escapeMarkdown(msg.text)}\n\n`
      if (msg.costTime) {
        mdContent += `*耗时 ${(msg.costTime / 1000).toFixed(2)}s*\n\n`
      }
    } else if (msg.role === 'tool_call') {
      mdContent += `## 🔧 正在调用工具: ${escapeMarkdown(msg.toolName || msg.text)}\n\n`
    } else if (msg.role === 'tool_result') {
      const toolLabel = msg.toolName ? ' (' + escapeMarkdown(msg.toolName) + ')' : ''
      const codeBlock = '\n```\n' + escapeMarkdown(msg.toolResult || msg.text) + '\n```\n\n'
      mdContent += '## ✅ 工具执行结果' + toolLabel + '\n\n' + codeBlock
    }
    mdContent += '---\n\n'
  }

  downloadFile(mdContent, `${assistantName}_对话记录_${formatTimestamp().replace(/[/:]/g, '-')}.md`, 'text/markdown;charset=utf-8')
}

export function exportChatToJson(messages: DisplayMessage[], assistantName: string): void {
  const data = {
    assistantName,
    exportedAt: formatTimestamp(),
    messageCount: messages.length,
    messages: messages.map((msg) => ({
      role: msg.role,
      text: msg.text,
      toolName: msg.toolName || null,
      toolResult: msg.toolResult || null,
      costTime: msg.costTime || null,
      isStreaming: msg.isStreaming || false,
    })),
  }

  downloadFile(JSON.stringify(data, null, 2), `${assistantName}_对话记录_${formatTimestamp().replace(/[/:]/g, '-')}.json`, 'application/json;charset=utf-8')
}

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
