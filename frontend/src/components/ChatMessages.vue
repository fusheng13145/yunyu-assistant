<template>
  <div ref="chatContentRef" class="h-full w-full geek-scroll px-6 py-8 space-y-6 min-h-0" @scroll="handleScroll">
    <!-- 空状态 -->
    <div v-if="messages.length === 0" class="flex flex-col items-center justify-center h-full text-center">
      <div class="w-16 h-16 rounded-xl flex items-center justify-center mb-5 border" style="background: var(--geek-input-bg); border-color: var(--geek-border)">
        <Bot class="w-8 h-8" style="color: var(--geek-text-muted)" />
      </div>
      <p class="text-lg font-bold tracking-tight mb-2" style="color: var(--geek-text)">开始对话</p>
      <p class="text-sm max-w-xs leading-relaxed" style="color: var(--geek-text-secondary)">在下方输入框中发送消息，与智能助手开始交流</p>
    </div>

    <!-- 消息列表 -->
    <template v-for="(msg, i) in messages" :key="i">
      <!-- tool_call 类型消息 -->
      <div v-if="msg.role === 'tool_call'" class="flex items-start">
        <div
          class="w-9 h-9 rounded-lg flex items-center justify-center mr-3 shrink-0"
          style="background: var(--geek-warning-bg)"
        >
          <Wrench class="w-4.5 h-4.5" style="color: var(--geek-warning)" />
        </div>
        <div class="geek-card rounded-lg px-4 py-3 max-w-[72%] border-l-2" style="border-left-color: var(--geek-warning)">
          <div class="flex items-center gap-2 mb-1.5">
            <div class="w-1.5 h-1.5 rounded-full animate-pulse" style="background: var(--geek-warning)"></div>
            <span class="text-xs font-medium" style="color: var(--geek-text-secondary)">正在调用工具</span>
            <span class="text-xs px-1.5 py-0.5 rounded-sm mono" style="color: var(--geek-warning); background: var(--geek-warning-bg)">{{ msg.toolName || msg.text }}</span>
          </div>
          <div v-if="msg.isStreaming" class="flex items-center gap-1">
            <div
              v-for="n in 3" :key="n" class="w-1 h-1 rounded-full animate-bounce"
              :style="{ animationDelay: `${n * 0.15}s`, background: 'var(--geek-warning)' }"
            ></div>
          </div>
        </div>
      </div>

      <!-- tool_result 类型消息 -->
      <div v-else-if="msg.role === 'tool_result'" class="flex items-start">
        <div
          class="w-9 h-9 rounded-lg flex items-center justify-center mr-3 shrink-0"
          style="background: var(--geek-success-bg)"
        >
          <CheckCircle class="w-4.5 h-4.5" style="color: var(--geek-success)" />
        </div>
        <div class="geek-card rounded-lg px-4 py-3 max-w-[72%] border-l-2" style="border-left-color: var(--geek-success)">
          <button
            @click="toggleToolResult(i)"
            class="w-full flex items-center justify-between gap-2 transition-colors rounded-md px-1 -mt-0.5 -mx-1 py-1 hover:bg-geek-input-bg"
            style="color: var(--geek-text-secondary)"
          >
            <div class="flex items-center gap-2">
              <CheckCircle class="w-3.5 h-3.5" style="color: var(--geek-success)" />
              <span class="text-xs font-medium" style="color: var(--geek-text)">工具执行结果</span>
              <span v-if="msg.toolName" class="text-xs px-1.5 py-0.5 rounded-sm mono" style="color: var(--geek-text-secondary); background: var(--geek-input-bg)">{{ msg.toolName }}</span>
            </div>
            <ChevronDown
              :class="[
                'w-3.5 h-3.5 transition-transform duration-200',
                expandedToolResults[i] ? 'rotate-180' : ''
              ]" style="color: var(--geek-text-muted)"
            />
          </button>
          <div v-if="expandedToolResults[i]" class="mt-2.5 overflow-hidden">
            <pre class="text-xs rounded-md p-3 overflow-x-auto whitespace-pre-wrap leading-relaxed geek-input border" style="border-color: var(--geek-border); color: var(--geek-text)">{{ formatJson(msg.toolResult || msg.text) }}</pre>
          </div>
        </div>
      </div>

      <!-- user / assistant 消息 -->
      <div v-else :class="msg.role === 'user' ? 'flex justify-end' : 'flex items-start'">
        <!-- AI 消息 -->
        <template v-if="msg.role === 'assistant'">
          <div
            class="w-9 h-9 rounded-lg flex items-center justify-center mr-3 shrink-0"
            style="background: var(--geek-primary)"
          >
            <Bot class="w-4.5 h-4.5" style="color: var(--geek-text-on-primary)" />
          </div>
          <div class="geek-card rounded-lg px-5 py-3.5 max-w-[72%] break-words">
            <div class="text-sm leading-relaxed md-content" style="color: var(--geek-text)" v-html="renderMarkdown(msg.text)"></div>
            <span v-if="msg.isStreaming" class="inline-block w-1.5 h-4 ml-0.5 align-middle animate-blink" style="background: var(--geek-primary)"></span>

            <!-- 工具调用折叠区域 -->
            <div v-if="msg.toolCalls && msg.toolCalls.length > 0" class="mt-3 space-y-2">
              <div
                v-for="(toolCall, ti) in msg.toolCalls" :key="ti"
                class="geek-card rounded-lg overflow-hidden border-l-2"
                style="border-left-color: var(--geek-accent)"
              >
                <button
                  @click="toggleToolCall(i, ti)"
                  class="w-full flex items-center justify-between px-3.5 py-2.5 text-left transition-colors hover:bg-geek-input-bg"
                >
                  <div class="flex items-center gap-2">
                    <Wrench class="w-3.5 h-3.5" style="color: var(--geek-accent)" />
                    <span class="text-xs font-medium" style="color: var(--geek-text-secondary)">工具调用</span>
                    <span class="text-xs px-1.5 py-0.5 rounded" style="color: var(--geek-accent); background: var(--geek-input-bg)">{{ toolCall.name }}</span>
                  </div>
                  <ChevronDown
                    :class="[
                      'w-3.5 h-3.5 transition-transform duration-200',
                      expandedToolCalls[`${i}-${ti}`] ? 'rotate-180' : ''
                    ]" style="color: var(--geek-text-muted)"
                  />
                </button>
                <div v-if="expandedToolCalls[`${i}-${ti}`]" class="px-3.5 pb-3 space-y-2 border-t" style="border-color: var(--geek-divider)">
                  <div>
                    <div class="text-xs mb-1 font-medium" style="color: var(--geek-text-muted)">参数</div>
                    <pre class="text-xs rounded-md p-2.5 overflow-x-auto whitespace-pre-wrap geek-input border" style="border-color: var(--geek-border); color: var(--geek-text)">{{ formatJson(toolCall.arguments) }}</pre>
                  </div>
                  <div>
                    <div class="text-xs mb-1 font-medium" style="color: var(--geek-text-muted)">结果</div>
                    <pre class="text-xs rounded-md p-2.5 overflow-x-auto whitespace-pre-wrap geek-input border" style="border-color: var(--geek-border); color: var(--geek-text)">{{ formatJson(toolCall.result) }}</pre>
                  </div>
                </div>
              </div>
            </div>

            <!-- 消息元信息 -->
            <div
              v-if="!msg.isStreaming && (msg.costTime || knowledgebaseFlag(msg.knowledgebase) !== 'none' || (msg.tokenUsage && (msg.tokenUsage.promptTokens || msg.tokenUsage.completionTokens)))"
              class="flex items-center gap-3 pt-2 mt-2 border-t flex-wrap" style="border-color: var(--geek-divider)"
            >
              <span v-if="msg.costTime" class="text-xs flex items-center" style="color: var(--geek-text-faint)">
                <Clock class="w-3 h-3 mr-1" />
                {{ (msg.costTime / 1000).toFixed(2) }}s
              </span>
              <span
                v-if="knowledgebaseFlag(msg.knowledgebase) === 'failed'"
                class="text-xs flex items-center gap-1.5 flex-wrap" style="color: var(--geek-warning)"
              >
                <AlertTriangle class="w-3 h-3" />
                知识库检索失败 · 本条回复未带参考
              </span>
              <span
                v-else-if="knowledgebaseFlag(msg.knowledgebase) === 'cited'"
                class="text-xs flex items-center gap-1.5 flex-wrap" style="color: var(--geek-text-faint)"
              >
                <BookOpen class="w-3 h-3" />
                引用文档 {{ msg.knowledgebase?.docCount || (msg.knowledgebase?.docName?.length || 0) }} 个
                <span v-if="msg.knowledgebase?.docName?.length" class="flex flex-wrap gap-1">
                  <span
                    v-for="(doc, di) in msg.knowledgebase?.docName || []"
                    :key="di"
                    class="px-1.5 py-0.5 rounded text-[10px]"
                    style="background: var(--geek-input-bg); color: var(--geek-text-secondary)"
                  >{{ doc }}</span>
                </span>
              </span>
              <span
                v-if="msg.tokenUsage && (msg.tokenUsage.promptTokens || msg.tokenUsage.completionTokens)"
                class="text-xs flex items-center gap-1" style="color: var(--geek-text-faint)"
              >
                <Cpu class="w-3 h-3" />
                {{ msg.tokenUsage.promptTokens || 0 }} prompt · {{ msg.tokenUsage.completionTokens || 0 }} completion
              </span>
            </div>
          </div>
        </template>

        <!-- 用户消息 -->
        <template v-else>
          <div
            class="rounded-lg px-5 py-3 max-w-[68%] break-words text-sm"
            style="background: var(--geek-primary); color: var(--geek-text-on-primary)"
          >
            {{ msg.text }}
            <span v-if="msg.isStreaming" class="inline-block w-1.5 h-4 ml-0.5 align-middle animate-blink" style="background: rgba(255,255,255,0.7)"></span>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch, reactive } from 'vue'
import { Bot, Clock, Wrench, ChevronDown, CheckCircle, BookOpen, Cpu, AlertTriangle } from 'lucide-vue-next'
import type { DisplayMessage } from '../types'
import { renderMarkdown } from '../utils/markdown'
import { knowledgebaseFlag } from '../utils/knowledgebaseFlag'

const props = withDefaults(defineProps<{
  messages: DisplayMessage[]
  autoScroll?: boolean
}>(), {
  autoScroll: true,
})

const emit = defineEmits<{
  'scroll-state-change': [state: { userScrolledManually: boolean; isAtBottom: boolean }]
}>()

const chatContentRef = ref<HTMLElement | null>(null)
const userScrolledManually = ref(false)
const lastScrollTime = ref(0)
const SCROLL_TIMEOUT = 10000
const expandedToolCalls = reactive<Record<string, boolean>>({})
const expandedToolResults = reactive<Record<number, boolean>>({})

const toggleToolCall = (msgIndex: number, toolIndex: number) => {
  const key = `${msgIndex}-${toolIndex}`
  expandedToolCalls[key] = !expandedToolCalls[key]
}

const toggleToolResult = (msgIndex: number) => {
  expandedToolResults[msgIndex] = !expandedToolResults[msgIndex]
}

const formatJson = (str: string): string => {
  try {
    const parsed = JSON.parse(str)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return str
  }
}

const handleScroll = () => {
  if (!chatContentRef.value) return

  const element = chatContentRef.value
  const isAtBottom = Math.abs(element.scrollHeight - element.clientHeight - element.scrollTop) < 5

  if (!isAtBottom) {
    userScrolledManually.value = true
    lastScrollTime.value = Date.now()
  } else {
    userScrolledManually.value = false
  }

  emit('scroll-state-change', {
    userScrolledManually: userScrolledManually.value,
    isAtBottom,
  })
}

const shouldAutoScroll = () => {
  if (!props.autoScroll) return false
  if (!userScrolledManually.value) return true

  const now = Date.now()
  if (now - lastScrollTime.value > SCROLL_TIMEOUT) {
    userScrolledManually.value = false
    return true
  }

  return false
}

const scrollToBottom = () => {
  if (!shouldAutoScroll()) return

  nextTick(() => {
    if (chatContentRef.value) {
      chatContentRef.value.scrollTop = chatContentRef.value.scrollHeight
    }
  })
}

const resetScrollState = () => {
  userScrolledManually.value = false
  lastScrollTime.value = 0
}

watch(() => props.messages, () => {
  scrollToBottom()
}, { deep: true })

defineExpose({
  scrollToBottom,
  resetScrollState,
})
</script>

<style scoped>
/* Markdown 渲染样式 */
.md-content :deep(.md-code-block) {
  background: var(--geek-input-bg);
  border: 1px solid var(--geek-border);
  border-radius: 8px;
  padding: 12px 14px;
  margin: 8px 0;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.6;
  font-family: 'JetBrains Mono', 'Consolas', 'Courier New', monospace;
}
.md-content :deep(.md-inline-code) {
  background: var(--geek-input-bg);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 0.9em;
  font-family: 'Consolas', 'Courier New', monospace;
}
.md-content :deep(.md-heading) {
  font-weight: 600;
  margin: 8px 0 4px;
  line-height: 1.4;
}
.md-content :deep(h1.md-heading) { font-size: 1.25rem; }
.md-content :deep(h2.md-heading) { font-size: 1.15rem; }
.md-content :deep(h3.md-heading) { font-size: 1.05rem; }
.md-content :deep(h4.md-heading) { font-size: 1rem; }
.md-content :deep(.md-list) {
  padding-left: 1.2em;
  margin: 4px 0;
}
.md-content :deep(.md-list li) {
  list-style: disc;
  margin: 2px 0;
}
.md-content :deep(ol.md-list li) {
  list-style: decimal;
}
.md-content :deep(.md-quote) {
  border-left: 3px solid var(--geek-accent);
  padding-left: 12px;
  margin: 6px 0;
  opacity: 0.85;
}
.md-content :deep(.md-paragraph) {
  margin: 4px 0;
}
.md-content :deep(.md-link) {
  color: var(--geek-primary);
  text-decoration: underline;
}
.md-content :deep(.md-image) {
  max-width: 100%;
  border-radius: 8px;
  display: block;
  margin: 8px 0;
}
.md-content :deep(strong) { font-weight: 600; }
.md-content :deep(em) { font-style: italic; }
</style>
