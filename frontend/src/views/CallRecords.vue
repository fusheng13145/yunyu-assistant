<template>
  <PageShell subtitle="通话记录">
    <template #list>
      <div class="pt-3 pb-2 flex items-center justify-between">
        <span class="text-xs mono tracking-widest" style="color: var(--geek-text-faint)">记录（{{ total }}）</span>
      </div>

      <div v-if="records.length === 0 && !loading" class="text-center py-10 px-3">
        <PhoneOff class="w-8 h-8 mx-auto mb-2" style="color: var(--geek-text-faint)" />
        <p class="text-sm" style="color: var(--geek-text-muted)">暂无通话记录</p>
        <p class="text-xs mt-1" style="color: var(--geek-text-faint)">在工作台发起语音通话后，记录会出现在这里</p>
      </div>

      <button
        v-for="record in records"
        :key="record.id"
        type="button"
        class="geek-listrow"
        :class="{ 'geek-listrow--active': selectedId === record.id }"
        :aria-current="selectedId === record.id ? 'true' : undefined"
        @click="selectRecord(record)"
      >
        <Bot class="w-4 h-4 flex-shrink-0" style="color: var(--geek-accent)" />
        <div class="flex-1 min-w-0">
          <div class="text-sm truncate">{{ record.assistantName || '未知助手' }}</div>
          <div class="text-xs mono truncate mt-0.5" style="color: var(--geek-text-muted)">
            {{ formatTime(record.startedAt) }} · {{ formatDuration(record.durationSec) }}
          </div>
        </div>
        <div class="flex items-center gap-1 flex-shrink-0">
          <span v-if="record.recording" class="rec-badge" title="含通话录音">REC</span>
          <span class="status-badge" :class="statusClass(record.status)">{{ statusLabel(record.status) }}</span>
        </div>
      </button>

      <div v-if="totalPages > 1" class="flex items-center justify-center gap-2 pt-3">
        <button
          class="geek-btn geek-btn-ghost geek-btn-sm"
          :disabled="page <= 1"
          @click="changePage(page - 1)"
        >
          上一页
        </button>
        <span class="text-xs" style="color: var(--geek-text-muted)">{{ page }} / {{ totalPages }}</span>
        <button
          class="geek-btn geek-btn-ghost geek-btn-sm"
          :disabled="page >= totalPages"
          @click="changePage(page + 1)"
        >
          下一页
        </button>
      </div>
    </template>

    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0 geek-surface">
      <div class="flex items-center gap-3">
        <h2 class="font-display text-xl font-bold tracking-tight text-geek">通话记录</h2>
        <span class="text-xs px-2 py-0.5 rounded-sm geek-badge bg-geek-tag-blue text-white">CALL_RECORDS</span>
      </div>
      <div class="flex items-center gap-3">
        <ThemeToggle :model-value="themeMode" @update:model-value="setTheme" />
      </div>
    </header>

    <div class="flex-1 min-h-0 overflow-y-auto geek-scroll px-4 sm:px-8 py-6">
      <!-- 用量统计 -->
      <div class="max-w-4xl mx-auto mb-6">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-bold tracking-tight" style="color: var(--geek-text)">用量统计</h3>
          <div class="flex items-center gap-1 rounded-md p-0.5 border" style="background: var(--geek-input-bg); border-color: var(--geek-border)">
            <button
              v-for="r in rangeOptions"
              :key="r"
              class="px-3 py-1 text-xs rounded-sm transition-colors font-medium"
              :class="statsRange === r ? 'bg-white shadow-sm' : ''"
              :style="statsRange === r ? 'color: var(--geek-text)' : 'color: var(--geek-text-muted)'"
              @click="changeRange(r)"
            >
              {{ r === 'day' ? '日' : r === 'week' ? '周' : '月' }}
            </button>
          </div>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-4">
          <div class="geek-card rounded-lg p-4">
            <p class="text-xs mono tracking-wide" style="color: var(--geek-text-muted)">CALL_COUNT / 通话次数</p>
            <p class="text-2xl font-bold mt-1.5 mono" style="color: var(--geek-text)">{{ stats?.callCount || 0 }}</p>
          </div>
          <div class="geek-card rounded-lg p-4">
            <p class="text-xs mono tracking-wide" style="color: var(--geek-text-muted)">DURATION / 总时长</p>
            <p class="text-2xl font-bold mt-1.5 mono" style="color: var(--geek-text)">{{ formatDuration(stats?.totalDurationSec) }}</p>
          </div>
          <div class="geek-card rounded-lg p-4">
            <p class="text-xs mono tracking-wide" style="color: var(--geek-text-muted)">MESSAGES / 消息数</p>
            <p class="text-2xl font-bold mt-1.5 mono" style="color: var(--geek-text)">{{ stats?.messageCount || 0 }}</p>
          </div>
        </div>

        <!-- 柱状图 -->
        <div class="geek-card rounded-lg p-5">
          <div class="flex items-end justify-between gap-2 h-28">
            <div
              v-for="(day, i) in stats?.days || []"
              :key="i"
              class="flex-1 flex flex-col items-center justify-end h-full"
              :title="`${day.date}：${day.callCount} 次`"
            >
              <span class="text-[10px] mb-1 mono" style="color: var(--geek-text-muted)">{{ day.callCount || '' }}</span>
              <div
                class="w-full max-w-[32px] rounded-t-sm transition-all"
                :style="{
                  height: (day.callCount / maxCallCount * 100) + '%',
                  background: 'var(--geek-accent)',
                  opacity: day.callCount === 0 ? 0.15 : 0.9
                }"
              ></div>
            </div>
          </div>
          <div class="flex items-center justify-between gap-2 mt-2">
            <span
              v-for="(day, i) in stats?.days || []"
              :key="'lbl-' + i"
              class="flex-1 text-center text-[10px] truncate mono"
              style="color: var(--geek-text-faint)"
            >{{ day.date.slice(5) }}</span>
          </div>
        </div>
      </div>

      <!-- 选中记录的详情（v2.63 起落在右栏，不再是遮罩弹窗） -->
      <div class="max-w-4xl mx-auto geek-card rounded-xl overflow-hidden">
        <div class="flex items-center justify-between px-6 py-4 border-b" style="border-color: var(--geek-divider)">
          <div>
            <h3 class="font-bold tracking-tight" style="color: var(--geek-text)">
              {{ detail?.assistantName || '通话详情' }}
            </h3>
            <p class="text-xs mt-0.5 mono" style="color: var(--geek-text-muted)">
              <template v-if="detail">
                {{ formatTime(detail.startedAt) }} · {{ formatDuration(detail.durationSec || 0) }} · {{ detail.messages.length }} 条消息
              </template>
              <template v-else>未选择记录</template>
            </p>
          </div>
          <span
            v-if="detail"
            class="status-badge"
            :class="statusClass(detail.status)"
          >{{ statusLabel(detail.status) }}</span>
        </div>

        <div class="px-6 py-5 space-y-4">
          <p v-if="selectedId === ''" class="text-sm text-center py-8" style="color: var(--geek-text-muted)">
            从左侧列表选择一条通话，详情与录音会显示在这里
          </p>
          <p v-else-if="detailFailed" class="text-sm text-center py-8" style="color: var(--geek-error)">
            详情加载失败，请重新点击左侧记录重试
          </p>
          <p v-else-if="detail === null" class="text-sm text-center py-8" style="color: var(--geek-text-muted)">
            加载中…
          </p>

          <template v-else>
            <!-- 通话录音回放 -->
            <div v-if="detail.recording" class="p-3 rounded-lg" style="background: var(--geek-input-bg); border: 1px solid var(--geek-border)">
              <div class="flex items-center gap-3">
                <Mic class="w-4 h-4 flex-shrink-0" style="color: var(--geek-accent)" />
                <div class="flex-1 min-w-0">
                  <p class="text-xs font-medium mb-1" style="color: var(--geek-text)">通话录音</p>
                  <audio v-if="recordingUrl" :src="recordingUrl" controls class="w-full" style="height: 32px"></audio>
                  <button
                    v-else-if="!recordingLoading"
                    @click="loadRecording(detail.id)"
                    class="geek-btn geek-btn-ghost geek-btn-sm"
                  >
                    播放录音
                  </button>
                  <span v-else class="text-xs" style="color: var(--geek-text-muted)">加载中…</span>
                </div>
              </div>
            </div>
            <div v-if="detail.messages.length === 0" class="text-center py-10 text-sm" style="color: var(--geek-text-muted)">
              本次通话无消息记录
            </div>
            <div
              v-for="(msg, i) in detail.messages"
              :key="i"
              class="flex"
              :class="msg.role === 0 ? 'justify-end' : 'justify-start'"
            >
              <div
                class="max-w-[75%] px-4 py-2.5 rounded-lg text-sm break-words"
                :style="msg.role === 0
                  ? 'background: var(--geek-primary); color: var(--geek-text-on-primary)'
                  : 'background: var(--geek-input-bg); color: var(--geek-text); border: 1px solid var(--geek-border)'"
              >
                {{ msg.message }}
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </PageShell>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Bot, Mic, PhoneOff } from 'lucide-vue-next'
import PageShell from '../components/PageShell.vue'
import { useTheme } from '../composables/useTheme'
import { useNotification } from '../composables/useNotification'
import ThemeToggle from '../components/ThemeToggle.vue'
import { fetchCallRecords, fetchCallRecordDetail, fetchRecordingBlob, fetchUsageStats } from '../api/callRecord'
import type { CallRecord, CallRecordDetail, UsageStats } from '../types'

const { show } = useNotification()
const { themeMode, setTheme } = useTheme()

const records = ref<CallRecord[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = 10
const total = ref(0)
const selectedId = ref('')
const detail = ref<CallRecordDetail | null>(null)
const detailFailed = ref(false)
// 录音回放
const recordingUrl = ref('')
const recordingLoading = ref(false)

// 用量统计
const stats = ref<UsageStats | null>(null)
const statsRange = ref<'day' | 'week' | 'month'>('week')
const rangeOptions: Array<'day' | 'week' | 'month'> = ['day', 'week', 'month']

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

const maxCallCount = computed(() => {
  if (!stats.value?.days?.length) return 1
  return Math.max(1, ...stats.value.days.map(d => d.callCount))
})

const loadStats = async () => {
  try {
    stats.value = await fetchUsageStats(statsRange.value)
  } catch (error) {
    console.error('获取用量统计失败:', error)
    show('用量统计加载失败，图表数据不是最新', 'error')
  }
}

const changeRange = (range: 'day' | 'week' | 'month') => {
  statsRange.value = range
  loadStats()
}

const loadRecords = async () => {
  loading.value = true
  try {
    const result = await fetchCallRecords(page.value, pageSize)
    records.value = result.list
    total.value = result.total
  } catch (error) {
    console.error('获取通话记录失败:', error)
    show(`通话记录加载失败：${(error as Error).message}`, 'error')
  } finally {
    loading.value = false
  }
}

const changePage = (p: number) => {
  page.value = p
  loadRecords()
}

const releaseRecording = () => {
  if (recordingUrl.value) {
    URL.revokeObjectURL(recordingUrl.value)
    recordingUrl.value = ''
  }
  recordingLoading.value = false
}

const selectRecord = async (record: CallRecord) => {
  selectedId.value = record.id
  detail.value = null
  detailFailed.value = false
  releaseRecording()
  try {
    detail.value = await fetchCallRecordDetail(record.id)
  } catch (error) {
    console.error('获取通话详情失败:', error)
    detailFailed.value = true
    show(`通话详情加载失败：${(error as Error).message}`, 'error')
  }
}

const loadRecording = async (id: string) => {
  recordingLoading.value = true
  try {
    const blob = await fetchRecordingBlob(id)
    if (recordingUrl.value) URL.revokeObjectURL(recordingUrl.value)
    recordingUrl.value = URL.createObjectURL(blob)
  } catch (error) {
    console.error('获取通话录音失败:', error)
    show(`录音拉取失败：${(error as Error).message}`, 'error')
  } finally {
    recordingLoading.value = false
  }
}

const formatTime = (t?: string) => {
  if (!t) return '—'
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const formatDuration = (sec?: number) => {
  if (!sec || sec <= 0) return '0 秒'
  if (sec < 60) return `${sec} 秒`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m} 分 ${s} 秒`
}

const statusLabel = (status: number) => {
  switch (status) {
    case 0: return '失败'
    case 1: return '进行中'
    case 2: return '正常结束'
    case 3: return '中断'
    default: return '未知'
  }
}

const statusClass = (status: number) => {
  switch (status) {
    case 0: return 'status-failed'
    case 1: return 'status-in-progress'
    case 2: return 'status-ended'
    case 3: return 'status-interrupted'
    default: return ''
  }
}

onMounted(() => {
  loadRecords()
  loadStats()
})
</script>

<style scoped>
/* 状态徽章：方正、等宽字体 */
.status-badge {
  padding: 3px 8px;
  border-radius: var(--radius-sm);
  font-size: 11px;
  font-weight: 600;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  letter-spacing: 0.03em;
}
.status-ended {
  background: var(--geek-success-bg);
  color: var(--geek-success);
}
.status-failed {
  background: var(--geek-error-bg);
  color: var(--geek-error);
}
.status-in-progress {
  background: var(--geek-info-bg);
  color: var(--geek-accent);
}
.status-interrupted {
  background: var(--geek-warning-bg);
  color: var(--geek-warning);
}
/* 录音徽章：方正红字 */
.rec-badge {
  padding: 3px 6px;
  border-radius: var(--radius-sm);
  font-size: 10px;
  font-weight: 700;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  letter-spacing: 0.05em;
  background: var(--geek-error-bg);
  color: var(--geek-error);
}
</style>
