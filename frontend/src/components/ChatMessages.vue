<template>
  <div ref="chatContentRef" class="h-full w-full overflow-y-auto px-5 py-10 space-y-8 transparent-scrollbar min-h-0"
    @scroll="handleScroll">
    <div v-for="(msg, i) in messages" :key="i"
      :class="msg.role === 'user' ? 'flex justify-end' : 'flex items-start'">
      <template v-if="msg.role === 'assistant'">
        <div
          class="w-12 h-12 bg-gradient-to-br from-purple-400 to-blue-400 rounded-full flex items-center justify-center mr-4 flex-shrink-0 shadow-lg text-2xl">
          <Bot class="w-8 h-8 text-white" />
        </div>
        <div
          class="rounded-3xl px-6 py-4 max-w-[70%] break-words bg-gradient-to-br from-blue-50 to-indigo-100 text-slate-800 shadow-xl border border-blue-200/50 text-base relative backdrop-blur-sm">
          <div class="leading-relaxed whitespace-pre-wrap">{{ msg.text }}</div>
          <span v-if="msg.isStreaming" class="inline-block w-2 h-5 bg-blue-500 ml-1 animate-pulse"></span>

          <div v-if="msg.toolCalls && msg.toolCalls.length > 0" class="mt-3 space-y-2">
            <div v-for="(toolCall, ti) in msg.toolCalls" :key="ti"
              class="bg-indigo-50/80 rounded-xl border border-indigo-200/50 overflow-hidden">
              <button @click="toggleToolCall(i, ti)"
                class="w-full flex items-center justify-between px-4 py-2.5 text-left hover:bg-indigo-100/50 transition-colors">
                <div class="flex items-center gap-2">
                  <Wrench class="w-4 h-4 text-indigo-500" />
                  <span class="text-sm font-medium text-indigo-700">工具调用</span>
                  <span class="text-xs text-indigo-500 bg-indigo-100 px-2 py-0.5 rounded-md">{{ toolCall.name }}</span>
                </div>
                <ChevronDown :class="[
                  'w-4 h-4 text-indigo-400 transition-transform duration-200',
                  expandedToolCalls[`${i}-${ti}`] ? 'rotate-180' : ''
                ]" />
              </button>
              <div v-if="expandedToolCalls[`${i}-${ti}`]" class="px-4 pb-3 space-y-2">
                <div>
                  <div class="text-xs text-indigo-400 mb-1 font-medium">参数</div>
                  <pre class="text-xs bg-white/60 rounded-lg p-2.5 overflow-x-auto whitespace-pre-wrap text-slate-700 border border-indigo-100">{{ formatJson(toolCall.arguments) }}</pre>
                </div>
                <div>
                  <div class="text-xs text-indigo-400 mb-1 font-medium">结果</div>
                  <pre class="text-xs bg-white/60 rounded-lg p-2.5 overflow-x-auto whitespace-pre-wrap text-slate-700 border border-indigo-100">{{ formatJson(toolCall.result) }}</pre>
                </div>
              </div>
            </div>
          </div>

          <div v-if="!msg.isStreaming && msg.costTime"
            class="flex justify-between items-end pt-2 border-t border-blue-200/30">
            <div
              class="text-xs text-slate-500 bg-white/60 px-2 py-1 rounded-full backdrop-blur-sm flex items-center">
              <span class="inline-flex items-center">
                <Clock class="w-3 h-3 mr-1" />
                {{ (msg.costTime / 1000).toFixed(2) }}s
              </span>
              <span
                v-if="msg.knowledgebase && (msg.knowledgebase.docCount || (msg.knowledgebase.docName && msg.knowledgebase.docName.length > 0))">
                &emsp;引用文档 {{ msg.knowledgebase.docCount || 0 }}个 : {{ msg.knowledgebase.docName?.join(', ') }}
              </span>
            </div>
          </div>
        </div>
      </template>
      <template v-else>
        <div
          class="rounded-3xl px-8 py-5 max-w-[70%] break-words bg-gradient-to-r from-blue-400 to-purple-400 text-white font-bold shadow-lg text-lg relative">
          {{ msg.text }}
          <span v-if="msg.isStreaming" class="inline-block w-2 h-5 bg-white ml-1 animate-pulse"></span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch, reactive } from 'vue'
import { Bot, Clock, Wrench, ChevronDown } from 'lucide-vue-next'
import type { DisplayMessage } from '../types'

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

const toggleToolCall = (msgIndex: number, toolIndex: number) => {
  const key = `${msgIndex}-${toolIndex}`
  expandedToolCalls[key] = !expandedToolCalls[key]
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
