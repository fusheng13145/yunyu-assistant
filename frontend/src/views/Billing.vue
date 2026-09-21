<template>
  <div class="geek-body theme-transition min-h-screen flex flex-col antialiased">
    <!-- 顶部导航 -->
    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0 geek-surface">
      <div class="flex items-center gap-3">
        <h1 class="font-display text-xl font-bold tracking-tight text-geek">用量配额</h1>
        <span class="text-xs px-2 py-0.5 rounded-sm geek-badge bg-geek-tag-gold text-white">USAGE</span>
      </div>
      <div class="flex items-center gap-3">
        <ThemeToggle :model-value="themeMode" @update:model-value="setTheme" />
        <button @click="router.back()" class="geek-btn geek-btn-ghost text-sm">
          <ArrowLeft class="w-4 h-4 inline mr-1" />
          返回
        </button>
      </div>
    </header>

    <main class="flex-1 p-6 max-w-5xl w-full mx-auto">
      <div class="mb-4 flex items-center justify-between">
        <span class="text-sm" style="color: var(--geek-text-secondary)">
          配额作用域：{{ usage?.scopeType === 'org' ? '组织级' : '用户级' }}
          <span class="mono text-xs ml-1" style="color: var(--geek-text-faint)">{{ usage?.scopeId }}</span>
        </span>
        <button @click="load" class="geek-btn geek-btn-ghost geek-btn-sm">
          <RefreshCw class="w-4 h-4 inline mr-1" />刷新
        </button>
      </div>

      <!-- 用量卡片 -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        <!-- 助手数量 -->
        <div class="geek-card rounded-xl p-5">
          <div class="flex items-center gap-2 mb-3">
            <Bot class="w-4 h-4" style="color: var(--geek-primary)" />
            <span class="text-sm" style="color: var(--geek-text-secondary)">助手数量（累计）</span>
          </div>
          <div class="flex items-end justify-between">
            <span class="text-3xl font-bold tabular-nums" style="color: var(--geek-text)">{{ usage?.current.assistantCount ?? '-' }}</span>
            <span class="text-sm tabular-nums" style="color: var(--geek-text-muted)">上限 {{ usage?.quota.assistantLimit ?? '-' }} · 剩余 {{ usage?.remaining.assistantRemaining ?? '-' }}</span>
          </div>
        </div>

        <!-- 单日通话次数 -->
        <div class="geek-card rounded-xl p-5">
          <div class="flex items-center gap-2 mb-3">
            <PhoneCall class="w-4 h-4" style="color: var(--geek-success)" />
            <span class="text-sm" style="color: var(--geek-text-secondary)">单日通话次数 / 时长</span>
          </div>
          <div class="flex items-end justify-between">
            <span class="text-3xl font-bold tabular-nums" style="color: var(--geek-text)">{{ usage?.current.dailyCallCount ?? '-' }}</span>
            <span class="text-sm tabular-nums" style="color: var(--geek-text-muted)">
              上限 {{ usage?.quota.dailyCallLimit ?? '-' }} 次 · 剩余 {{ usage?.remaining.dailyCallRemaining ?? '-' }}
            </span>
          </div>
          <p class="text-xs mt-2 tabular-nums" style="color: var(--geek-text-muted)">
            时长 {{ fmtSec(usage?.current.dailyCallSec) }} / {{ fmtSec(usage?.quota.dailyCallSecLimit) }} · 剩余 {{ fmtSec(usage?.remaining.dailyCallSecRemaining) }}
          </p>
        </div>

        <!-- 单日消息量 -->
        <div class="geek-card rounded-xl p-5 md:col-span-2">
          <div class="flex items-center gap-2 mb-3">
            <MessageSquare class="w-4 h-4" style="color: var(--geek-warning)" />
            <span class="text-sm" style="color: var(--geek-text-secondary)">单日消息量</span>
          </div>
          <div class="flex items-end justify-between">
            <span class="text-3xl font-bold tabular-nums" style="color: var(--geek-text)">{{ usage?.current.dailyMsgCount ?? '-' }}</span>
            <span class="text-sm tabular-nums" style="color: var(--geek-text-muted)">
              上限 {{ usage?.quota.dailyMsgLimit ?? '-' }} 条 · 剩余 {{ usage?.remaining.dailyMsgRemaining ?? '-' }}
            </span>
          </div>
        </div>
      </div>

      <p v-if="usage === null" class="text-center text-sm mt-10" style="color: var(--geek-text-muted)">加载中…</p>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, RefreshCw, Bot, PhoneCall, MessageSquare } from 'lucide-vue-next'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useTheme } from '../composables/useTheme'
import { fetchUsage, type QuotaUsage } from '../api/billing'

const { themeMode, setTheme } = useTheme()
const router = useRouter()
const usage = ref<QuotaUsage | null>(null)

const fmtSec = (sec?: number) => {
  if (sec === undefined || sec === null) return '-'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m} 分 ${s} 秒`
}

const load = async () => {
  try {
    usage.value = await fetchUsage()
  } catch (error) {
    console.error('加载用量失败:', error)
  }
}

onMounted(load)
</script>