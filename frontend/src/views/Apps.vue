<template>
  <PageShell subtitle="应用管理">
    <template #list>
      <div class="pt-3 pb-2 flex items-center justify-between gap-2">
        <span class="text-xs mono tracking-widest" style="color: var(--geek-text-faint)">应用（{{ apps.length }}）</span>
        <button @click="showCreate = true" class="geek-btn geek-btn-primary geek-btn-sm">
          <Plus class="w-3.5 h-3.5" />创建
        </button>
      </div>

      <div v-if="apps.length === 0" class="text-center py-10 px-3">
        <KeyRound class="w-8 h-8 mx-auto mb-2" style="color: var(--geek-text-faint)" />
        <p class="text-sm" style="color: var(--geek-text-muted)">暂无应用</p>
        <p class="text-xs mt-1" style="color: var(--geek-text-faint)">创建应用后获得 API Key</p>
      </div>

      <button
        v-for="app in apps"
        :key="app.id"
        type="button"
        class="geek-listrow"
        :class="{ 'geek-listrow--active': selectedAppId === app.id }"
        :aria-current="selectedAppId === app.id ? 'true' : undefined"
        @click="selectedAppId = app.id"
      >
        <div class="flex-1 min-w-0">
          <div class="text-sm truncate">{{ app.appName }}</div>
          <div class="text-xs mono truncate mt-0.5" style="color: var(--geek-text-muted)">
            {{ scopeList(app.scope).join(' / ') || '无能力' }}
          </div>
        </div>
        <span
          class="text-xs px-1.5 py-0.5 rounded-sm mono flex-shrink-0"
          :style="app.enabled
            ? { background: 'var(--geek-success-bg)', color: 'var(--geek-success)' }
            : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }"
        >{{ app.enabled ? '启用' : '停用' }}</span>
      </button>
    </template>

    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0 geek-surface">
      <div class="flex items-center gap-3">
        <h2 class="font-display text-xl font-bold tracking-tight text-geek">应用管理</h2>
        <span class="text-xs px-2 py-0.5 rounded-sm geek-badge bg-geek-tag-purple text-white">OPENAPI</span>
      </div>
      <div class="flex items-center gap-3">
        <ThemeToggle :model-value="themeMode" @update:model-value="setTheme" />
      </div>
    </header>

    <div class="flex-1 min-h-0 overflow-y-auto geek-scroll p-6">
      <div class="max-w-5xl w-full mx-auto">
        <p class="text-sm mb-4" style="color: var(--geek-text-secondary)">
          第三方应用按能力接入开放 API（文本对话 / 电话外呼 / 语音会话），未开通的端点返回 403；配额计入你的账号用量
        </p>

        <!-- 未归属的拒绝（无 Key / 错 Key / 握手超限）：爆破针对的不是某个应用，所以这一格对所有登录用户可见 -->
        <div v-if="!denialFailed && unattributed.length > 0" class="geek-card rounded-xl px-4 py-3 mb-4 flex flex-wrap items-center gap-x-2 gap-y-1">
          <span class="text-xs" style="color: var(--geek-text-secondary)">未识别凭据的调用：</span>
          <span
            v-for="row in unattributed"
            :key="row.kind"
            class="text-xs px-1.5 py-0.5 rounded-sm mono"
            style="background: var(--geek-warning-bg); color: var(--geek-warning)"
          >{{ row.kindLabel }} ×{{ row.count }}</span>
          <span class="text-xs" style="color: var(--geek-text-faint)">
            近 {{ denialWindow }} 小时累计，不含任何凭据内容；台账在内存里，重启清零、只算当前实例
          </span>
        </div>
        <p v-else-if="denialFailed" class="text-xs mb-4" style="color: var(--geek-warning)">
          拒绝台账读取失败：各应用的"被拒"不可信，请刷新重试
        </p>

        <p v-if="selectedApp === null" class="text-sm" style="color: var(--geek-text-muted)">
          从左侧选择一个应用查看能力、Webhook 与拒绝台账
        </p>

        <div v-else class="geek-card rounded-xl overflow-hidden">
          <div class="px-4 py-3 border-b geek-divider flex items-center justify-between">
            <div class="flex items-center gap-2 min-w-0">
              <span class="text-sm font-medium truncate" style="color: var(--geek-text)">{{ selectedApp.appName }}</span>
              <span
                class="text-xs px-1.5 py-0.5 rounded-sm mono flex-shrink-0"
                :style="selectedApp.enabled
                  ? { background: 'var(--geek-success-bg)', color: 'var(--geek-success)' }
                  : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }"
              >{{ selectedApp.enabled ? '启用' : '停用' }}</span>
            </div>
            <button
              @click="onRevoke(selectedApp)"
              class="text-xs px-2 py-1 rounded hover:opacity-70 flex-shrink-0"
              style="color: var(--geek-error)"
            >
              吊销
            </button>
          </div>

          <dl class="divide-y" style="border-color: var(--geek-divider)">
            <div class="px-4 py-3 flex items-start gap-4">
              <dt class="w-28 flex-shrink-0 text-xs mono" style="color: var(--geek-text-muted)">开通能力</dt>
              <dd class="flex-1 min-w-0">
                <div class="flex flex-wrap items-center gap-1">
                  <span
                    v-for="scope in scopeList(selectedApp.scope)"
                    :key="scope"
                    class="text-xs px-1.5 py-0.5 rounded-sm mono"
                    style="background: var(--geek-primary-bg); color: var(--geek-primary)"
                  >{{ scope }}</span>
                  <span v-if="scopeList(selectedApp.scope).length === 0" class="text-xs" style="color: var(--geek-text-faint)">无能力</span>
                  <!-- 能力可编辑（v2.46）：明文 Key 自 v2.45 起不可回读，"吊销后重建"已不再是可行的调整路径 -->
                  <button
                    @click="openEditScopes(selectedApp)"
                    class="text-xs px-1.5 py-0.5 rounded hover:opacity-70"
                    style="color: var(--geek-text-muted)"
                  >
                    编辑
                  </button>
                </div>
              </dd>
            </div>

            <div class="px-4 py-3 flex items-start gap-4">
              <dt class="w-28 flex-shrink-0 text-xs mono" style="color: var(--geek-text-muted)">Webhook</dt>
              <dd class="flex-1 min-w-0 text-xs break-all" style="color: var(--geek-text)">{{ selectedApp.webhookUrl || '—' }}</dd>
            </div>

            <!-- 拒绝台账（v2.50）：内存聚合，读数失败与"真的零次被拒"必须长成两种样子 -->
            <div class="px-4 py-3 flex items-start gap-4">
              <dt class="w-28 flex-shrink-0 text-xs mono" style="color: var(--geek-text-muted)">近 {{ denialWindow }} 小时被拒</dt>
              <dd class="flex-1 min-w-0">
                <span v-if="cellFor(selectedApp.id).tone === 'unavailable'" class="text-xs" style="color: var(--geek-warning)">台账读不到</span>
                <span v-else-if="cellFor(selectedApp.id).tone === 'quiet'" class="text-xs" style="color: var(--geek-text-faint)">0 次</span>
                <div v-else class="flex flex-wrap items-center gap-1">
                  <span
                    v-for="row in cellFor(selectedApp.id).rows"
                    :key="row.kind"
                    class="text-xs px-1.5 py-0.5 rounded-sm mono"
                    style="background: var(--geek-warning-bg); color: var(--geek-warning)"
                  >{{ row.kindLabel }} ×{{ row.count }}</span>
                </div>
              </dd>
            </div>

            <div class="px-4 py-3 flex items-start gap-4">
              <dt class="w-28 flex-shrink-0 text-xs mono" style="color: var(--geek-text-muted)">创建时间</dt>
              <dd class="flex-1 min-w-0 text-xs" style="color: var(--geek-text-muted)">{{ formatTime(selectedApp.createdAt) }}</dd>
            </div>

            <div class="px-4 py-3 flex items-start gap-4">
              <dt class="w-28 flex-shrink-0 text-xs mono" style="color: var(--geek-text-muted)">应用 ID</dt>
              <dd class="flex-1 min-w-0 text-xs break-all mono" style="color: var(--geek-text-faint)">{{ selectedApp.id }}</dd>
            </div>
          </dl>
        </div>
      </div>
    </div>

    <!-- 创建应用弹窗 -->
    <div v-if="showCreate" class="fixed inset-0 z-50 flex items-center justify-center" style="background: rgba(0,0,0,0.45)">
      <div class="geek-card rounded-xl p-6 w-full max-w-lg mx-4">
        <h3 class="font-display text-lg font-bold mb-4" style="color: var(--geek-text)">创建应用</h3>
        <input v-model="createForm.appName" type="text" maxlength="64" placeholder="应用名称（必填）" class="geek-input w-full px-3 py-2 rounded mb-3" />
        <input v-model="createForm.webhookUrl" type="text" placeholder="Webhook 回调 URL（可选，第三方调用时可接收事件回调）" class="geek-input w-full px-3 py-2 rounded mb-4" />
        <!-- 能力勾选：勾选即白名单，服务端按端点判定，缺能力回 403（判定全在服务端，这里只负责拼串） -->
        <ScopePicker
          v-model="createForm.scopes"
          :options="scopeOptions"
          legend="开通能力"
          hint="全不选＝只开通文本对话；能力之后可以在列表里编辑，不必吊销重建"
          class="mb-5"
        />
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
          本次开通能力：<span class="mono" style="color: var(--geek-text)">{{ scopeList(createdApp.scopes).join(' / ') || '无' }}</span>
          （调用未开通的端点会返回 403；能力可在列表里随时编辑，改完下一个请求即生效）
        </p>
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

    <!-- 编辑能力弹窗（v2.46）：整串替换，服务端拒绝空集，所以全不选时保存按钮不可用 -->
    <div v-if="editTarget" class="fixed inset-0 z-50 flex items-center justify-center" style="background: rgba(0,0,0,0.45)">
      <div class="geek-card rounded-xl p-6 w-full max-w-lg mx-4">
        <h3 class="font-display text-lg font-bold mb-1" style="color: var(--geek-text)">编辑能力</h3>
        <p class="text-xs mb-4" style="color: var(--geek-text-muted)">
          「{{ editTarget.appName }}」当前：
          <span class="mono" style="color: var(--geek-text)">{{ scopeList(editTarget.scope).join(' / ') || '无能力' }}</span>
        </p>
        <ScopePicker
          v-model="editScopes"
          :options="scopeOptions"
          legend="开通能力"
          hint="保存即整串替换：取消勾选的能力下一请求起返回 403。至少要保留一项，要停掉全部能力请吊销应用。"
          class="mb-5"
        />
        <div class="flex justify-end gap-2">
          <button @click="editTarget = null" class="geek-btn geek-btn-ghost geek-btn-sm">取消</button>
          <button
            @click="onSaveScopes"
            :disabled="editScopes.length === 0 || savingScopes"
            class="geek-btn geek-btn-primary geek-btn-sm"
            :class="{ 'opacity-40 cursor-not-allowed': editScopes.length === 0 || savingScopes }"
          >
            {{ savingScopes ? '保存中…' : '保存' }}
          </button>
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
  </PageShell>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Plus, KeyRound } from 'lucide-vue-next'
import PageShell from '../components/PageShell.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import ScopePicker from '../components/ScopePicker.vue'
import { useTheme } from '../composables/useTheme'
import { useNotification } from '../composables/useNotification'
import { fetchApps, createApp, revokeApp, updateAppScopes, fetchDenials } from '../api/openapi'
import type { ApiAppItem, ApiAppCreated, DenialRow } from '../api/openapi'
import { ledgerCellFor, unattributedRows } from '../utils/denialLedger'

const { themeMode, setTheme } = useTheme()
const { show } = useNotification()

const apps = ref<ApiAppItem[]>([])
const selectedAppId = ref('')
const selectedApp = computed(() => apps.value.find(a => a.id === selectedAppId.value) ?? null)
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ appName: '', webhookUrl: '', scopes: ['chat'] as string[] })
const createdApp = ref<ApiAppCreated | null>(null)
const revokeTarget = ref<ApiAppItem | null>(null)
const editTarget = ref<ApiAppItem | null>(null)
const editScopes = ref<string[]>([])
const savingScopes = ref(false)

/** 拒绝台账（v2.50 · 候选 ㉜）：读数、实际窗口、以及"读没读到"这个状态本身 */
const denialRows = ref<DenialRow[]>([])
const denialWindow = ref(24)
const denialFailed = ref(false)

/** 三态判定（读不到 / 零次 / 有拒绝）在 utils/denialLedger.ts，与门禁共用同一份实现 */
const cellFor = (appId: string) => ledgerCellFor(denialRows.value, appId, denialFailed.value)
const unattributed = computed(() => unattributedRows(denialRows.value))

/** 能力清单与服务端 ApiApp.ALL_SCOPES 同序同名；勾选框只负责拼逗号串，判定全在服务端 */
const scopeOptions = [
  { value: 'chat', label: '文本对话（POST /api/open/chat）' },
  { value: 'call', label: '电话外呼（POST /api/open/call，产生运营商费用）' },
  { value: 'voice', label: '语音会话（WS /api/open/ws-voice/*）' },
]

/** 逗号串 → 能力数组（空/缺省都归一为空数组，供"无能力"占位显示） */
const scopeList = (value?: string) => (value || '').split(',').map(s => s.trim()).filter(Boolean)

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
  // 选中行在增删改后可能已不存在：回落到第一条，右栏不会出现"选中的是谁"的空档
  if (!apps.value.some(a => a.id === selectedAppId.value)) {
    selectedAppId.value = apps.value[0]?.id ?? ''
  }
  await loadDenials()
}

/**
 * 拒绝台账单独一条错误路径：它是附加的可观测面，读失败不能把应用列表也带崩，
 * 但也不能显示成"0 次被拒"——那等于把故障伪装成好消息，所以置 denialFailed 让列显式承认读不到。
 */
const loadDenials = async () => {
  try {
    const result = await fetchDenials()
    denialRows.value = result.rows
    denialWindow.value = result.windowHours
    denialFailed.value = false
  } catch (error) {
    console.error('加载拒绝台账失败:', error)
    denialRows.value = []
    denialFailed.value = true
  }
}

const onCreate = async () => {
  creating.value = true
  try {
    createdApp.value = await createApp(
      createForm.value.appName.trim(),
      createForm.value.webhookUrl.trim() || undefined,
      createForm.value.scopes.join(','),
    )
    createForm.value = { appName: '', webhookUrl: '', scopes: ['chat'] }
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

const openEditScopes = (app: ApiAppItem) => {
  editTarget.value = app
  editScopes.value = scopeList(app.scope)
}

const onSaveScopes = async () => {
  if (!editTarget.value) return
  savingScopes.value = true
  try {
    const result = await updateAppScopes(editTarget.value.id, editScopes.value.join(','))
    show(`「${editTarget.value.appName}」已开通：${scopeList(result.scopes).join(' / ')}`, 'success')
    editTarget.value = null
    await load()
  } catch (error) {
    console.error('更新应用能力失败:', error)
    show(`能力更新失败：${(error as Error).message}`, 'error')
  } finally {
    savingScopes.value = false
  }
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
