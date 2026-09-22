<template>
  <div class="geek-body theme-transition min-h-screen flex flex-col antialiased">
    <!-- 顶部导航 -->
    <header class="flex items-center justify-between px-6 py-4 border-b shrink-0 geek-surface">
      <div class="flex items-center gap-3">
        <h1 class="font-display text-xl font-bold tracking-tight text-geek">组织管理</h1>
        <span class="text-xs px-2 py-0.5 rounded-sm geek-badge bg-geek-tag-blue text-white">TEAM</span>
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
      <!-- 组织列表 -->
      <section v-if="selectedOrg === null">
        <div class="flex items-center justify-between mb-4">
          <span class="text-sm" style="color: var(--geek-text-secondary)">我的组织（组织内资源按角色共享：owner 管理成员 / editor 增删改 / viewer 只读使用）</span>
          <button @click="showCreateOrg = true" class="geek-btn geek-btn-primary geek-btn-sm">
            <Plus class="w-4 h-4 inline mr-1" />创建组织
          </button>
        </div>

        <div v-if="orgs.length === 0" class="geek-card rounded-xl py-16 text-center">
          <Users class="w-10 h-10 mx-auto mb-3" style="color: var(--geek-text-faint)" />
          <p class="text-sm" style="color: var(--geek-text-muted)">暂无组织</p>
          <p class="text-xs mt-1" style="color: var(--geek-text-faint)">创建组织后可邀请成员共同协作</p>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
          <div v-for="org in orgs" :key="org.id" class="geek-card rounded-xl p-4 cursor-pointer" @click="openOrg(org.id)" @keydown.enter="openOrg(org.id)">
            <div class="flex items-center justify-between mb-2">
              <span class="font-display font-bold" style="color: var(--geek-text)">{{ org.name }}</span>
              <span class="text-xs px-2 py-0.5 rounded-sm mono" style="background: var(--geek-input-bg); color: var(--geek-accent)">
                {{ roleLabel(org.ownerUserId) }}
              </span>
            </div>
            <p class="text-xs" style="color: var(--geek-text-muted)">
              {{ org.description || '暂无描述' }}
            </p>
            <p class="text-xs mt-2 mono" style="color: var(--geek-text-faint)">
              ID {{ org.id.slice(0, 8) }} · 创建于 {{ formatTime(org.createdAt) }}
            </p>
          </div>
        </div>
      </section>

      <!-- 成员管理面板 -->
      <section v-else>
        <div class="flex items-center justify-between mb-4">
          <div class="flex items-center gap-2">
            <button @click="selectedOrg = null" class="geek-btn geek-btn-ghost geek-btn-sm">
              <ChevronLeft class="w-4 h-4" />返回列表
            </button>
            <h2 class="font-display text-lg font-bold" style="color: var(--geek-text)">
              {{ selectedOrg.name }}
            </h2>
            <span
              class="text-xs px-2 py-0.5 rounded-sm" :style="myRole === 'owner'
                ? { background: 'var(--geek-primary-bg)', color: 'var(--geek-primary)' }
                : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }"
            >
              {{ myRole ? roleName(myRole) : '-' }}
            </span>
          </div>
          <button
            v-if="myRole === 'owner'"
            @click="onDeleteOrg"
            class="geek-btn geek-btn-sm py-1.5"
            style="color: var(--geek-error)"
          >
            删除组织
          </button>
        </div>

        <div class="geek-card rounded-xl overflow-hidden">
          <div class="px-4 py-3 border-b geek-divider flex items-center justify-between">
            <span class="text-sm font-medium" style="color: var(--geek-text)">成员（{{ members.length }}）</span>
            <button
              v-if="myRole === 'owner'"
              @click="showAddMember = true"
              class="geek-btn geek-btn-primary geek-btn-sm"
            >
              <UserPlus class="w-4 h-4 inline mr-1" />添加成员
            </button>
          </div>

          <table class="w-full text-sm">
            <thead>
              <tr class="border-b geek-divider" style="background: var(--geek-bg-subtle)">
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">成员</th>
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">角色</th>
                <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">加入时间</th>
                <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="member in members" :key="member.id" class="border-b geek-divider">
                <td class="px-4 py-2.5" style="color: var(--geek-text)">
                  {{ member.username || member.userId }}
                  <span v-if="member.userId === selectedOrg.ownerUserId" class="text-xs px-1.5 py-0.5 rounded-sm mono ml-1" style="background: var(--geek-success-bg); color: var(--geek-success)">创建者</span>
                </td>
                <td class="px-4 py-2.5">
                  <select
                    v-if="myRole === 'owner' && member.userId !== localStorageUserId"
                    :value="member.role"
                    @change="onRoleChange(member, ($event.target as HTMLSelectElement).value)"
                    class="geek-input px-2 py-1 rounded text-xs"
                  >
                    <option value="editor">editor</option>
                    <option value="viewer">viewer</option>
                  </select>
                  <span
                    v-else class="text-xs px-1.5 py-0.5 rounded-sm mono" :style="member.role === 'owner'
                      ? { background: 'var(--geek-success-bg)', color: 'var(--geek-success)' }
                      : member.role === 'editor'
                        ? { background: 'var(--geek-primary-bg)', color: 'var(--geek-primary)' }
                        : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }"
                  >
                    {{ roleName(member.role) }}
                  </span>
                </td>
                <td class="px-4 py-2.5 text-center text-xs" style="color: var(--geek-text-muted)">{{ formatTime(member.joinedAt) }}</td>
                <td class="px-4 py-2.5 text-center">
                  <button
                    v-if="myRole === 'owner' && member.userId !== localStorageUserId"
                    @click="onRemoveMember(member)"
                    class="text-xs px-2 py-1 rounded hover:opacity-70"
                    style="color: var(--geek-error)"
                  >
                    移除
                  </button>
                  <button
                    v-else-if="member.userId === localStorageUserId && myRole !== 'owner'"
                    @click="onExitOrg"
                    class="text-xs px-2 py-1 rounded hover:opacity-70"
                    style="color: var(--geek-error)"
                  >
                    退出
                  </button>
                  <span v-else class="text-xs" style="color: var(--geek-text-faint)">—</span>
                </td>
              </tr>
              <tr v-if="members.length === 0">
                <td colspan="4" class="px-4 py-10 text-center text-sm" style="color: var(--geek-text-muted)">暂无成员</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 创建组织弹窗 -->
      <div v-if="showCreateOrg" class="fixed inset-0 z-50 flex items-center justify-center" style="background: rgba(0,0,0,0.45)">
        <div class="geek-card rounded-xl p-6 w-full max-w-md mx-4">
          <h3 class="font-display text-lg font-bold mb-4" style="color: var(--geek-text)">创建组织</h3>
          <input v-model="createForm.name" type="text" maxlength="64" placeholder="组织名称（必填）" class="geek-input w-full px-3 py-2 rounded mb-3" />
          <input v-model="createForm.description" type="text" placeholder="组织描述（可选）" class="geek-input w-full px-3 py-2 rounded mb-5" />
          <div class="flex justify-end gap-2">
            <button @click="showCreateOrg = false" class="geek-btn geek-btn-ghost geek-btn-sm">取消</button>
            <button @click="onCreateOrg" :disabled="!createForm.name.trim() || creating" class="geek-btn geek-btn-primary geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': !createForm.name.trim() || creating }">
              {{ creating ? '创建中…' : '创建' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 添加成员弹窗 -->
      <div v-if="showAddMember" class="fixed inset-0 z-50 flex items-center justify-center" style="background: rgba(0,0,0,0.45)">
        <div class="geek-card rounded-xl p-6 w-full max-w-md mx-4">
          <h3 class="font-display text-lg font-bold mb-4" style="color: var(--geek-text)">添加成员</h3>
          <input v-model="addForm.username" type="text" placeholder="对方用户名" class="geek-input w-full px-3 py-2 rounded mb-3" />
          <select v-model="addForm.role" class="geek-input w-full px-3 py-2 rounded mb-5">
            <option value="editor">editor（编辑者）</option>
            <option value="viewer">viewer（只读）</option>
          </select>
          <div class="flex justify-end gap-2">
            <button @click="showAddMember = false" class="geek-btn geek-btn-ghost geek-btn-sm">取消</button>
            <button @click="onAddMember" :disabled="!addForm.username.trim()" class="geek-btn geek-btn-primary geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': !addForm.username.trim() }">添加</button>
          </div>
          <p v-if="memberError" class="text-xs mt-3" style="color: var(--geek-error)">{{ memberError }}</p>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Plus, Users, ChevronLeft, UserPlus } from 'lucide-vue-next'
import ThemeToggle from '../components/ThemeToggle.vue'
import { useTheme } from '../composables/useTheme'
import {
  createOrg, fetchOrgs, fetchOrgMembers, addOrgMember,
  changeOrgMemberRole, removeOrgMember, deleteOrg,
} from '../api/org'
import type { Org, OrgMember } from '../types'

const { themeMode, setTheme } = useTheme()
const router = useRouter()

const orgs = ref<Org[]>([])
const selectedOrg = ref<Org | null>(null)
const members = ref<OrgMember[]>([])
const showCreateOrg = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', description: '' })
const showAddMember = ref(false)
const addForm = ref({ username: '', role: 'editor' })
const memberError = ref('')
const localStorageUserId = localStorage.getItem('userId') || ''
const myRole = computed(() =>
  members.value.find((m) => m.userId === localStorageUserId)?.role ?? undefined,
)

const roleName = (role?: string) =>
  role === 'owner' ? 'owner' : role === 'editor' ? 'editor' : role === 'viewer' ? 'viewer' : '-'

const roleLabel = (ownerUserId: string) =>
  localStorageUserId === ownerUserId ? 'OWNER' : 'MEMBER'

const formatTime = (value?: string) => {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const loadOrgs = async () => {
  try {
    orgs.value = await fetchOrgs()
  } catch (error) {
    console.error('加载组织列表失败:', error)
  }
}

const openOrg = async (orgId: string) => {
  const org = orgs.value.find((o) => o.id === orgId)
  if (!org) return
  selectedOrg.value = org
  try {
    members.value = await fetchOrgMembers(orgId)
  } catch (error) {
    console.error('加载成员失败:', error)
  }
}

const onCreateOrg = async () => {
  creating.value = true
  try {
    await createOrg(createForm.value.name.trim(), createForm.value.description.trim() || undefined)
    showCreateOrg.value = false
    createForm.value = { name: '', description: '' }
    await loadOrgs()
  } catch (error) {
    console.error('创建组织失败:', error)
  } finally {
    creating.value = false
  }
}

const onAddMember = async () => {
  if (!selectedOrg.value) return
  memberError.value = ''
  try {
    await addOrgMember(selectedOrg.value.id, addForm.value.username.trim(), addForm.value.role)
    showAddMember.value = false
    addForm.value = { username: '', role: 'editor' }
    members.value = await fetchOrgMembers(selectedOrg.value.id)
  } catch (error) {
    memberError.value = error instanceof Error ? error.message : '添加失败'
  }
}

const onRoleChange = async (member: OrgMember, role: string) => {
  if (!selectedOrg.value) return
  try {
    await changeOrgMemberRole(selectedOrg.value.id, member.userId, role)
    members.value = await fetchOrgMembers(selectedOrg.value.id)
  } catch (error) {
    console.error('修改角色失败:', error)
  }
}

const onRemoveMember = async (member: OrgMember) => {
  if (!selectedOrg.value) return
  if (!window.confirm(`确认移除成员 ${member.username || member.userId}？`)) return
  try {
    await removeOrgMember(selectedOrg.value.id, member.userId)
    members.value = await fetchOrgMembers(selectedOrg.value.id)
  } catch (error) {
    console.error('移除成员失败:', error)
  }
}

const onExitOrg = async () => {
  if (!selectedOrg.value) return
  if (!window.confirm('确认退出该组织？')) return
  try {
    await removeOrgMember(selectedOrg.value.id, localStorageUserId)
    selectedOrg.value = null
    await loadOrgs()
  } catch (error) {
    console.error('退出组织失败:', error)
  }
}

const onDeleteOrg = async () => {
  if (!selectedOrg.value) return
  if (!window.confirm(`确认删除组织「${selectedOrg.value.name}」？该操作不可恢复。`)) return
  try {
    await deleteOrg(selectedOrg.value.id)
    selectedOrg.value = null
    await loadOrgs()
  } catch (error) {
    console.error('删除组织失败:', error)
  }
}

onMounted(loadOrgs)
</script>