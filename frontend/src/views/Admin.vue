<template>
  <div class="geek-body theme-transition min-h-screen flex flex-col antialiased">
    <!-- 顶部导航 -->
    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0 geek-surface">
      <div class="flex items-center gap-3">
        <h1 class="font-display text-xl font-bold tracking-tight text-geek">管理后台</h1>
        <span class="text-xs px-2 py-0.5 rounded-sm geek-badge bg-geek-tag-purple text-white">ADMIN</span>
      </div>
      <div class="flex items-center gap-3">
        <ThemeToggle :model-value="themeMode" @update:model-value="setTheme" />
        <button @click="router.back()" class="geek-btn geek-btn-ghost text-sm">
          <ArrowLeft class="w-4 h-4 inline mr-1" />
          返回
        </button>
      </div>
    </header>

    <main class="flex-1 p-6 max-w-6xl w-full mx-auto">
      <!-- 用量总览 -->
      <section class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 mb-6">
        <div v-for="card in overviewCards" :key="card.label" class="geek-card rounded-xl p-4">
          <div class="flex items-center gap-2 mb-2">
            <component :is="card.icon" class="w-4 h-4" :style="{ color: card.color }" />
            <span class="text-xs" style="color: var(--geek-text-muted)">{{ card.label }}</span>
          </div>
          <div class="text-2xl font-bold tabular-nums" style="color: var(--geek-text)">
            {{ overview ? card.value(overview) : '-' }}
          </div>
        </div>
      </section>

      <!-- 审计日志 / 用户列表 / 数据归档 Tabs -->
      <div class="flex items-center gap-2 mb-4">
        <button
          @click="activeTab = 'audit'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'audit' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >审计日志</button>
        <button
          @click="activeTab = 'users'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'users' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >用户列表</button>
        <button
          @click="activeTab = 'archive'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'archive' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >数据归档</button>
      </div>

      <!-- 数据归档面板 -->
      <section v-if="activeTab === 'archive'" class="geek-card rounded-xl overflow-hidden">
        <div class="px-4 py-3 border-b geek-divider flex items-center justify-between">
          <span class="text-sm font-medium" style="color: var(--geek-text)">数据归档概览（超期数据将归档至 *_archive 表并清理录音文件）</span>
        </div>
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b geek-divider" style="background: var(--geek-bg-subtle)">
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">数据表</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">总量</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-warning)">超期待归档</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">保留天数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in archiveRows" :key="row.key" class="border-b geek-divider">
              <td class="px-4 py-2.5 font-medium" style="color: var(--geek-text)">{{ row.label }}</td>
              <td class="px-4 py-2.5 tabular-nums" style="color: var(--geek-text)">{{ row.stat?.total ?? '-' }}</td>
              <td class="px-4 py-2.5 tabular-nums" style="color: var(--geek-warning)">{{ row.stat?.expired ?? '-' }}</td>
              <td class="px-4 py-2.5 tabular-nums" style="color: var(--geek-text-secondary)">{{ row.stat?.retentionDays ?? '-' }} 天</td>
            </tr>
            <tr v-if="!archiveOverview">
              <td colspan="4" class="px-4 py-10 text-center text-sm" style="color: var(--geek-text-muted)">加载中…</td>
            </tr>
          </tbody>
        </table>
        <div class="flex flex-col items-center gap-3 px-4 py-5 border-t geek-divider">
          <span class="text-xs" style="color: var(--geek-text-muted)">
            定时归档：{{ archiveOverview?.scheduleEnabled ? '已开启（' + (archiveOverview?.cron || '') + '）' : '已关闭' }}
          </span>
          <div class="flex items-center gap-3">
            <button
              @click="onRunArchive"
              :disabled="archiveRunning"
              class="geek-btn geek-btn-primary geek-btn-sm"
              :class="{ 'opacity-40 cursor-not-allowed': archiveRunning }"
            >{{ archiveRunning ? '归档中…' : '立即归档' }}</button>
          </div>
          <span v-if="archiveResult" class="text-xs mono text-center" style="color: var(--geek-text-secondary)">
            归档完成：消息 {{ archiveResult.recordsArchived }} · 通话 {{ archiveResult.callRecordsArchived }} · 审计 {{ archiveResult.auditLogsArchived }} 条；录音删除 {{ archiveResult.recordingsDeleted }} 个（失败 {{ archiveResult.recordingsFailed }}）
          </span>
          <span v-else-if="archiveError" class="text-xs" style="color: var(--geek-error)">{{ archiveError }}</span>
        </div>
      </section>

      <!-- 审计日志表格 -->
      <section v-if="activeTab === 'audit'" class="geek-card rounded-xl overflow-hidden">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b geek-divider" style="background: var(--geek-bg-subtle)">
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">动作</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">目标</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">用户</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">IP</th>
              <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">结果</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in auditLogs" :key="log.id" class="border-b geek-divider">
              <td class="px-4 py-2.5">
                <span class="text-xs px-1.5 py-0.5 rounded-sm mono" style="background: var(--geek-input-bg); color: var(--geek-accent)">{{ log.action }}</span>
              </td>
              <td class="px-4 py-2.5" style="color: var(--geek-text)">{{ log.targetType || '-' }}{{ log.targetId ? `#${log.targetId.slice(0, 8)}` : '' }}</td>
              <td class="px-4 py-2.5 mono text-xs" style="color: var(--geek-text-secondary)">{{ log.userId || '-' }}</td>
              <td class="px-4 py-2.5 mono text-xs" style="color: var(--geek-text-secondary)">{{ log.ip || '-' }}</td>
              <td class="px-4 py-2.5 text-center">
                <span class="text-xs px-1.5 py-0.5 rounded-sm" :style="log.result === 1
                  ? { background: 'var(--geek-success-bg)', color: 'var(--geek-success)' }
                  : { background: 'var(--geek-error-bg)', color: 'var(--geek-error)' }">
                  {{ log.result === 1 ? '成功' : '失败' }}
                </span>
              </td>
              <td class="px-4 py-2.5 text-xs" style="color: var(--geek-text-muted)">{{ formatTime(log.createdAt) }}</td>
            </tr>
            <tr v-if="auditLogs.length === 0">
              <td colspan="6" class="px-4 py-10 text-center text-sm" style="color: var(--geek-text-muted)">暂无审计日志</td>
            </tr>
          </tbody>
        </table>
        <div v-if="auditLogTotal > 10" class="flex items-center justify-between px-4 py-3 border-t geek-divider">
          <span class="text-xs" style="color: var(--geek-text-muted)">共 {{ auditLogTotal }} 条</span>
          <div class="flex items-center gap-2">
            <button @click="prevAuditPage" :disabled="auditPage <= 1" class="geek-btn geek-btn-ghost geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': auditPage <= 1 }">上一页</button>
            <span class="text-xs" style="color: var(--geek-text-muted)">{{ auditPage }} / {{ auditTotalPages }}</span>
            <button @click="nextAuditPage" :disabled="auditPage >= auditTotalPages" class="geek-btn geek-btn-ghost geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': auditPage >= auditTotalPages }">下一页</button>
          </div>
        </div>
      </section>

      <!-- 用户列表表格 -->
      <section v-else class="geek-card rounded-xl overflow-hidden">
        <div class="px-4 py-3 border-b geek-divider">
          <input
            v-model="userKeyword"
            type="text"
            placeholder="搜索用户名 / 昵称"
            class="geek-input w-full max-w-xs px-3 py-1.5 rounded-md text-sm"
            @keyup.enter="onSearchUser"
          />
        </div>
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b geek-divider" style="background: var(--geek-bg-subtle)">
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">用户名</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">昵称</th>
              <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">角色</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">邮箱</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">手机号</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">注册时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id" class="border-b geek-divider">
              <td class="px-4 py-2.5 font-medium" style="color: var(--geek-text)">{{ u.username }}</td>
              <td class="px-4 py-2.5" style="color: var(--geek-text-secondary)">{{ u.nickname || '-' }}</td>
              <td class="px-4 py-2.5 text-center">
                <span class="text-xs px-1.5 py-0.5 rounded-sm" :style="u.role === 'admin'
                  ? { background: 'var(--geek-tag-purple)', color: '#fff' }
                  : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }">
                  {{ u.role === 'admin' ? '管理员' : '用户' }}
                </span>
              </td>
              <td class="px-4 py-2.5 text-xs" style="color: var(--geek-text-muted)">{{ u.email || '-' }}</td>
              <td class="px-4 py-2.5 text-xs" style="color: var(--geek-text-muted)">{{ u.phone || '-' }}</td>
              <td class="px-4 py-2.5 text-xs" style="color: var(--geek-text-muted)">{{ formatTime(u.createdAt) }}</td>
            </tr>
            <tr v-if="users.length === 0">
              <td colspan="6" class="px-4 py-10 text-center text-sm" style="color: var(--geek-text-muted)">暂无用户</td>
            </tr>
          </tbody>
        </table>
        <div v-if="userTotal > 10" class="flex items-center justify-between px-4 py-3 border-t geek-divider">
          <span class="text-xs" style="color: var(--geek-text-muted)">共 {{ userTotal }} 位用户</span>
          <div class="flex items-center gap-2">
            <button @click="prevUserPage" :disabled="userPage <= 1" class="geek-btn geek-btn-ghost geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': userPage <= 1 }">上一页</button>
            <span class="text-xs" style="color: var(--geek-text-muted)">{{ userPage }} / {{ userTotalPages }}</span>
            <button @click="nextUserPage" :disabled="userPage >= userTotalPages" class="geek-btn geek-btn-ghost geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': userPage >= userTotalPages }">下一页</button>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, Users, Bot, PhoneCall, MessageSquare, MessagesSquare, ScrollText,
} from 'lucide-vue-next'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useTheme } from '../composables/useTheme'
import { fetchOverview, fetchAuditLogs, fetchUsers, fetchArchiveOverview, runArchive, type AdminOverview, type AdminAuditLog, type ArchiveOverview, type ArchiveRunResult } from '../api/admin'
import type { User } from '../types'

const { themeMode, setTheme } = useTheme()
const router = useRouter()

const PAGE_SIZE = 10

// 概览
const overview = ref<AdminOverview | null>(null)
const overviewCards = computed(() => [
  { label: '用户', icon: Users, color: 'var(--geek-accent)', value: (o: AdminOverview) => o.userCount },
  { label: '助手', icon: Bot, color: 'var(--geek-primary)', value: (o: AdminOverview) => o.assistantCount },
  { label: '通话', icon: PhoneCall, color: 'var(--geek-success)', value: (o: AdminOverview) => o.callRecordCount },
  { label: '消息', icon: MessageSquare, color: 'var(--geek-warning)', value: (o: AdminOverview) => o.messageCount },
  { label: '会话', icon: MessagesSquare, color: 'var(--geek-tag-purple)', value: (o: AdminOverview) => o.sessionCount },
  { label: '审计', icon: ScrollText, color: 'var(--geek-error)', value: (o: AdminOverview) => o.auditLogCount },
])

// Tabs
const activeTab = ref<'audit' | 'users' | 'archive'>('audit')

// 数据归档
const archiveOverview = ref<ArchiveOverview | null>(null)
const archiveRunning = ref(false)
const archiveResult = ref<ArchiveRunResult | null>(null)
const archiveError = ref('')

const archiveRows = computed(() => [
  { key: 'records', label: '对话消息（records）', stat: archiveOverview.value?.records },
  { key: 'callRecords', label: '通话记录（call_records）', stat: archiveOverview.value?.callRecords },
  { key: 'auditLogs', label: '审计日志（audit_logs）', stat: archiveOverview.value?.auditLogs },
])

const loadArchiveOverview = async () => {
  try {
    archiveOverview.value = await fetchArchiveOverview()
  } catch (error) {
    console.error('加载数据归档概览失败:', error)
  }
}

const onRunArchive = async () => {
  if (archiveRunning.value) return
  archiveRunning.value = true
  archiveError.value = ''
  try {
    archiveResult.value = await runArchive()
    await loadArchiveOverview()
  } catch (error) {
    archiveError.value = error instanceof Error ? error.message : '归档执行失败'
  } finally {
    archiveRunning.value = false
  }
}

// 审计日志分页
const auditLogs = ref<AdminAuditLog[]>([])
const auditPage = ref(1)
const auditLogTotal = ref(0)
const auditTotalPages = computed(() => Math.max(1, Math.ceil(auditLogTotal.value / PAGE_SIZE)))

const loadAuditLogs = async () => {
  try {
    const result = await fetchAuditLogs(auditPage.value, PAGE_SIZE)
    auditLogs.value = result.list
    auditLogTotal.value = result.total
  } catch (error) {
    console.error('加载审计日志失败:', error)
  }
}
const prevAuditPage = () => { if (auditPage.value > 1) { auditPage.value--; loadAuditLogs() } }
const nextAuditPage = () => { if (auditPage.value < auditTotalPages.value) { auditPage.value++; loadAuditLogs() } }

// 用户分页
const users = ref<User[]>([])
const userPage = ref(1)
const userKeyword = ref('')
const userTotal = ref(0)
const userTotalPages = computed(() => Math.max(1, Math.ceil(userTotal.value / PAGE_SIZE)))

const loadUsers = async () => {
  try {
    const result = await fetchUsers(userPage.value, PAGE_SIZE, userKeyword.value)
    users.value = result.list
    userTotal.value = result.total
  } catch (error) {
    console.error('加载用户列表失败:', error)
  }
}
const onSearchUser = () => { userPage.value = 1; loadUsers() }
const prevUserPage = () => { if (userPage.value > 1) { userPage.value--; loadUsers() } }
const nextUserPage = () => { if (userPage.value < userTotalPages.value) { userPage.value++; loadUsers() } }

const formatTime = (value?: string) => {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  await Promise.all([
    fetchOverview().then(data => { overview.value = data }).catch(e => console.error('加载概览失败:', e)),
    loadAuditLogs(),
    loadUsers(),
    loadArchiveOverview(),
  ])
})
</script>