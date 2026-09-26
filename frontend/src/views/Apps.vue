<template>
  <div class="geek-body theme-transition min-h-screen flex flex-col antialiased">
    <!-- 顶部导航 -->
    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0 geek-surface">
      <div class="flex items-center gap-3">
        <h1 class="font-display text-xl font-bold tracking-tight text-geek">应用管理</h1>
        <span class="text-xs px-2 py-0.5 rounded-sm geek-badge bg-geek-tag-purple text-white">OPENAPI</span>
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
      <div class="flex items-center justify-between mb-4">
        <span class="text-sm" style="color: var(--geek-text-secondary)">
          第三方应用用于开放 API 接入（文本对话 / 语音会话），配额计入你的账号用量
        </span>
        <button @click="showCreate = true" class="geek-btn geek-btn-primary geek-btn-sm">
          <Plus class="w-4 h-4 inline mr-1" />创建应用
        </button>
      </div>

      <div v-if="apps.length === 0" class="geek-card rounded-xl py-16 text-center">
        <KeyRound class="w-10 h-10 mx-auto mb-3" style="color: var(--geek-text-faint)" />
        <p class="text-sm" style="color: var(--geek-text-muted)">暂无应用</p>
        <p class="text-xs mt-1" style="color: var(--geek-text-faint)">创建应用后获得 API Key，即可接入开放 OpenAPI</p>
      </div>

      <div class="geek-card rounded-xl overflow-hidden" v-else>
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b geek-divider" style="background: var(--geek-bg-subtle)">
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">应用名称</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">作用域</th>
              <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">Webhook</th>
              <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">状态</th>
              <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">创建时间</th>
              <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="app in apps" :key="app.id" class="border-b geek-divider">
              <td class="px-4 py-2.5" style="color: var(--geek-text)">{{ app.appName }}</td>
              <td class="px-4 py-2.5">
                <span class="text-xs px-1.5 py-0.5 rounded-sm mono" style="background: var(--geek-primary-bg); color: var(--geek-primary)">{{ app.scope }}</span>
              </td>
              <td class="px-4 py-2.5 text-xs">{{ app.webhookUrl || '—' }}</td>
              <td class="px-4 py-2.5 text-center">
                <span
                  class="text-xs px-1.5 py-0.5 rounded-sm mono"
                  :style="app.enabled
                    ? { background: 'var(--geek-success-bg)', color: 'var(--geek-success)' }
                    : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }"
                >
                  {{ app.enabled ? '启用' : '停用' }}
                </span>
              </td>
              <td class="px-4 py-2.5 text-center text-xs" style="color: var(--geek-text-muted)">{{ formatTime(app.createdAt) }}</td>
              <td class="px-4 py-2.5 text-center">
                <button @click="onRevoke(app)" class="text-xs px-2 py-1 rounded hover:opacity-70" style="color: var(--geek-error)">
                  吊销
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 创建应用弹窗 -->
      <div v-if="showCreate" class="fixed inset-0 z-50 flex items-center justify-center" style="background: rgba(0,0,0,0.45)">
        <div class="geek-card rounded-xl p-6 w-full max-w-lg mx-4">
          <h3 class="font-display text-lg font-bold mb-4" style="color: var(--geek-text)">创建应用</h3>
          <input v-model="createForm.appName" type="text" maxlength="64" placeholder="应用名称（必填）" class="geek-input w-full px-3 py-2 rounded mb-3" />
          <input v-model="createForm.webhookUrl" type="text" placeholder="Webhook 回调 URL（可选，第三方调用时可接收事件回调）" class="geek-input w-full px-3 py-2 rounded mb-5" />
          <div class="flex justify-end gap-2">
            <button @click="showCreate = false" class="geek-btn geek-btn-ghost geek-btn-sm">取消</button>
            <button @click="onCreate" :disabled="!createForm.appName.trim() || creating" class="geek-btn geek-btn-primary geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': !createForm.appName.trim() || creating }">
              {{ creating ? '创建中…' : '创建' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 创建成功：一次性展示 app_key -->
      <div v-if="createdApp" class="fixed inset-0 z-50 flex items-center justify-center" style="background: rgba(0,0,0,0.45)">
        <div class="geek-card rounded-xl p-6 w-full max-w-lg mx-4">
          <h3 class="font-display text-lg font-bold mb-2" style="color: var(--geek-text)">创建成功</h3>
          <p class="text-xs mb-4" style="color: var(--geek-text-muted)">API Key 仅展示一次，请立即保存。</p>
          <div class="rounded px-3 py-2 mono text-xs break-all mb-3" style="background: var(--geek-input-bg); color: var(--geek-accent)">
            {{ createdApp.appKey }}
          </div>
          <p class="text-xs mb-3" style="color: var(--geek-text-muted)">
            Webhook Secret（用于回调签名校验，同样仅展示一次）：
          </p>
          <div class="rounded px-3 py-2 mono text-xs break-all mb-5" style="background: var(--geek-input-bg); color: var(--geek-text-secondary)">
            {{ createdApp.webhookSecret }}
          </div>
          <div class="flex justify-end gap-2">
            <button @click="createdApp = null; showCreate = false" class="geek-btn geek-btn-primary geek-btn-sm">我已保存</button>
          </div>
        </div>
      </div>

      <!-- 吊销确认弹窗 -->
      <div v-if="revokeTarget" class="fixed inset-0 z-50 flex items-center justify-center" style="background: rgba(0,0,0,0.45)">
        <div class="geek-card rounded-xl p-6 w-full max-w-md mx-4">
          <h3 class="font-display text-lg font-bold mb-2" style="color: var(--geek-text)">吊销应用</h3>
          <p class="text-sm mb-5" style="color: var(--geek-text-muted)">
            确认吊销「{{ revokeTarget.appName }}」？吊销后其 API Key 即刻失效，不可恢复。
          </p>
          <div class="flex justify-end gap-2">
            <button @click="revokeTarget = null" class="geek-btn geek-btn-ghost geek-btn-sm">取消</button>
            <button @click="onRevokeConfirm" class="geek-btn geek-btn-sm" style="color: var(--geek-error)">确认吊销</button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Plus, KeyRound } from 'lucide-vue-next'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useTheme } from '../composables/useTheme'
import { useNotification } from '../composables/useNotification'
import { fetchApps, createApp, revokeApp } from '../api/openapi'
import type { ApiAppItem, ApiAppCreated } from '../api/openapi'

const { themeMode, setTheme } = useTheme()
const { show } = useNotification()
const router = useRouter()

const apps = ref<ApiAppItem[]>([])
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ appName: '', webhookUrl: '' })
const createdApp = ref<ApiAppCreated | null>(null)
const revokeTarget = ref<ApiAppItem | null>(null)

const formatTime = (value?: string) => {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const load = async () => {
  try {
    apps.value = await fetchApps()
  } catch (error) {
    console.error('加载应用列表失败:', error)
    show('应用列表加载失败，请刷新页面重试', 'error')
  }
}

const onCreate = async () => {
  creating.value = true
  try {
    createdApp.value = await createApp(createForm.value.appName.trim(), createForm.value.webhookUrl.trim() || undefined)
    createForm.value = { appName: '', webhookUrl: '' }
    await load()
  } catch (error) {
    console.error('创建应用失败:', error)
    show(`创建应用失败：${(error as Error).message}`, 'error')
  } finally {
    creating.value = false
  }
}

const onRevoke = (app: ApiAppItem) => {
  revokeTarget.value = app
}

const onRevokeConfirm = async () => {
  if (!revokeTarget.value) return
  try {
    await revokeApp(revokeTarget.value.id)
    revokeTarget.value = null
    await load()
  } catch (error) {
    console.error('吊销应用失败:', error)
    show(`吊销应用失败：${(error as Error).message}`, 'error')
  }
}

onMounted(load)
</script>