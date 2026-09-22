/**
 * 轻量 Markdown 渲染器（无外部依赖）
 * 支持：代码块、行内代码、标题、加粗、斜体、无序/有序列表、引用、链接、图片、换行
 * 安全：先做 HTML 转义，避免 XSS 注入
 */

/** 转义 HTML 特殊字符，防止 XSS */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 渲染行内 Markdown（加粗、斜体、行内代码、链接） */
function renderInline(text: string): string {
  let html = escapeHtml(text)
  // 行内代码 `code`
  html = html.replace(/`([^`]+)`/g, '<code class="md-inline-code">$1</code>')
  // 加粗 **text**
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // 斜体 *text*
  html = html.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
  // 图片 ![alt](url)：须先于链接处理，否则 ![alt](url) 会被当作带感叹号的链接
  html = html.replace(
    /!\[([^\]]*)\]\((https?:\/\/[^)\s]+)\)/g,
    '<img src="$2" alt="$1" class="md-image" loading="lazy" />'
  )
  // 链接 [text](url)
  html = html.replace(
    /\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener noreferrer" class="md-link">$1</a>'
  )
  return html
}

/**
 * 渲染 Markdown 为 HTML 字符串
 */
export function renderMarkdown(markdown: string): string {
  if (!markdown) return ''

  const lines = markdown.replace(/\r\n/g, '\n').split('\n')
  const output: string[] = []
  let inCodeBlock = false
  let codeLines: string[] = []
  let listStack: 'ul' | 'ol' | null = null

  const closeList = () => {
    if (listStack === 'ul') {
      output.push('</ul>')
      listStack = null
    } else if (listStack === 'ol') {
      output.push('</ol>')
      listStack = null
    }
  }

  for (const rawLine of lines) {
    const line = rawLine.trimEnd()

    // 代码块开关
    if (line.trim().startsWith('```')) {
      if (inCodeBlock) {
        const codeHtml = escapeHtml(codeLines.join('\n'))
        output.push(`<pre class="md-code-block"><code>${codeHtml}</code></pre>`)
        codeLines = []
        inCodeBlock = false
      } else {
        closeList()
        inCodeBlock = true
      }
      continue
    }

    if (inCodeBlock) {
      codeLines.push(rawLine)
      continue
    }

    const trimmed = line.trim()
    if (!trimmed) {
      closeList()
      continue
    }

    // 标题
    const heading = trimmed.match(/^(#{1,4})\s+(.*)$/)
    if (heading) {
      closeList()
      const level = heading[1].length
      output.push(`<h${level} class="md-heading">${renderInline(heading[2])}</h${level}>`)
      continue
    }

    // 引用
    if (trimmed.startsWith('&gt; ') || trimmed.startsWith('> ')) {
      closeList()
      const quote = trimmed.replace(/^&gt;\s?/, '').replace(/^>\s?/, '')
      output.push(`<blockquote class="md-quote">${renderInline(quote)}</blockquote>`)
      continue
    }

    // 无序列表
    if (/^[-*+]\s+/.test(trimmed)) {
      if (listStack !== 'ul') {
        closeList()
        output.push('<ul class="md-list">')
        listStack = 'ul'
      }
      output.push(`<li>${renderInline(trimmed.replace(/^[-*+]\s+/, ''))}</li>`)
      continue
    }

    // 有序列表
    if (/^\d+\.\s+/.test(trimmed)) {
      if (listStack !== 'ol') {
        closeList()
        output.push('<ol class="md-list">')
        listStack = 'ol'
      }
      output.push(`<li>${renderInline(trimmed.replace(/^\d+\.\s+/, ''))}</li>`)
      continue
    }

    // 普通段落
    closeList()
    output.push(`<p class="md-paragraph">${renderInline(trimmed)}</p>`)
  }

  // 收尾：未闭合的代码块 / 列表
  if (inCodeBlock && codeLines.length > 0) {
    output.push(`<pre class="md-code-block"><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
  }
  closeList()

  return output.join('')
}
