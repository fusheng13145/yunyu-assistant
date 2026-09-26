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

      <!-- 审计日志 / 用户列表 / 数据归档 / 配额管理 Tabs -->
      <div class="flex items-center gap-2 mb-4">
        <button
          @click="activeTab = 'audit'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'audit' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >
          审计日志
        </button>
        <button
          @click="activeTab = 'users'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'users' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >
          用户列表
        </button>
        <button
          @click="activeTab = 'archive'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'archive' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >
          数据归档
        </button>
        <button
          @click="activeTab = 'quotas'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'quotas' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >
          配额管理
        </button>
        <button
          @click="activeTab = 'invites'"
          class="geek-btn geek-btn-sm"
          :class="activeTab === 'invites' ? 'geek-btn-primary' : 'geek-btn-ghost'"
        >
          邀请码
        </button>
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
            >
              {{ archiveRunning ? '归档中…' : '立即归档' }}
            </button>
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
                <span
                  class="text-xs px-1.5 py-0.5 rounded-sm" :style="log.result === 1
                    ? { background: 'var(--geek-success-bg)', color: 'var(--geek-success)' }
                    : { background: 'var(--geek-error-bg)', color: 'var(--geek-error)' }"
                >
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
      <section v-else-if="activeTab === 'users'" class="geek-card rounded-xl overflow-hidden">
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
                <span
                  class="text-xs px-1.5 py-0.5 rounded-sm" :style="u.role === 'admin'
                    ? { background: 'var(--geek-tag-purple)', color: '#fff' }
                    : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }"
                >
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

      <!-- 配额管理面板 -->
      <section v-else-if="activeTab === 'quotas'" class="flex flex-col gap-4">
        <!-- 兜底配额与生效规则 -->
        <div class="geek-card rounded-xl p-4">
          <div class="flex items-center justify-between mb-2">
            <span class="text-sm font-medium" style="color: var(--geek-text)">未单独配置时生效的兜底配额</span>
            <span class="text-xs mono" style="color: var(--geek-text-muted)">来源：APP_QUOTA_* 环境变量</span>
          </div>
          <div v-if="quotaDefaults" class="grid grid-cols-2 md:grid-cols-4 gap-2">
            <div v-for="field in quotaFields" :key="field.key" class="rounded-md px-3 py-2" style="background: var(--geek-bg-subtle)">
              <div class="text-xs" style="color: var(--geek-text-muted)">{{ field.label }}</div>
              <div class="text-lg font-bold tabular-nums mt-0.5" style="color: var(--geek-text)">
                {{ quotaDefaults[field.key] ?? '未设' }} <span class="text-xs font-normal">{{ field.unit }}</span>
              </div>
            </div>
          </div>
          <span v-else class="text-xs" style="color: var(--geek-text-muted)">兜底配额加载中…</span>
          <p class="text-xs leading-relaxed mt-3" style="color: var(--geek-text-secondary)">
            生效顺序：组织配置 → 用户配置 → 上述兜底值。加入组织的用户按<b>组织</b>配额计算用量，对其单独配的用户行不生效；
            用量与剩余额度在「用量账单」页只读展示。
          </p>
          <p class="text-xs leading-relaxed mt-1.5" style="color: var(--geek-warning)">
            兜底值改动需改环境变量并重启；对外开放注册前建议先把「单日消息量」「单日通话时长」压到可接受的成本区间。
          </p>
        </div>

        <!-- 已配置作用域 -->
        <div class="geek-card rounded-xl overflow-hidden">
          <div class="px-4 py-3 border-b geek-divider text-sm font-medium" style="color: var(--geek-text)">
            已配置作用域（{{ quotas.length }}）
          </div>
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b geek-divider" style="background: var(--geek-bg-subtle)">
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">作用域</th>
                <th v-for="field in quotaFields" :key="field.key" class="text-right px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">{{ field.label }}（{{ field.unit }}）</th>
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">更新时间</th>
                <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="q in quotas" :key="q.id" class="border-b geek-divider">
                <td class="px-4 py-2.5">
                  <span
                    class="text-xs px-1.5 py-0.5 rounded-sm mr-2" :style="q.scopeType === 'org'
                      ? { background: 'var(--geek-tag-purple)', color: '#fff' }
                      : { background: 'var(--geek-input-bg)', color: 'var(--geek-text-secondary)' }"
                  >
                    {{ q.scopeType === 'org' ? '组织' : '用户' }}
                  </span>
                  <span class="mono text-xs" style="color: var(--geek-text-secondary)" :title="q.scopeId">{{ scopeLabel(q) }}</span>
                </td>
                <td v-for="field in quotaFields" :key="field.key" class="px-4 py-2.5 text-right tabular-nums" style="color: var(--geek-text)">
                  {{ q[field.key] ?? '-' }}
                </td>
                <td class="px-4 py-2.5 text-xs" style="color: var(--geek-text-muted)">{{ formatTime(q.updatedAt) }}</td>
                <td class="px-4 py-2.5 text-center">
                  <button @click="startEditQuota(q)" class="geek-btn geek-btn-ghost geek-btn-sm">编辑</button>
                </td>
              </tr>
              <tr v-if="quotas.length === 0">
                <td :colspan="quotaFields.length + 3" class="px-4 py-10 text-center text-sm" style="color: var(--geek-text-muted)">
                  尚无配置行：所有作用域均使用上方兜底值
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 新建 / 编辑配置行 -->
        <div class="geek-card rounded-xl p-4">
          <div class="text-sm font-medium mb-3" style="color: var(--geek-text)">
            {{ quotaEditingId ? '编辑配额配置' : '新建配额配置' }}
          </div>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label class="block text-xs mb-1" style="color: var(--geek-text-muted)">作用域类型</label>
              <select v-model="quotaForm.scopeType" class="geek-input w-full px-3 py-1.5 rounded-md text-sm">
                <option value="user">用户（user）</option>
                <option value="org">组织（org）</option>
              </select>
            </div>
            <div>
              <label class="block text-xs mb-1" style="color: var(--geek-text-muted)">
                {{ quotaForm.scopeType === 'org' ? '组织 ID' : '用户' }}
              </label>
              <select
                v-if="quotaForm.scopeType === 'user'"
                v-model="quotaForm.scopeId"
                class="geek-input w-full px-3 py-1.5 rounded-md text-sm"
              >
                <option value="">请选择用户</option>
                <option v-for="u in quotaUserOptions" :key="u.id" :value="u.id">{{ u.username }}{{ u.nickname ? `（${u.nickname}）` : '' }}</option>
              </select>
              <input
                v-else
                v-model="quotaForm.scopeId"
                type="text"
                placeholder="组织 UUID"
                class="geek-input w-full px-3 py-1.5 rounded-md text-sm mono"
              />
            </div>
          </div>
          <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mt-3">
            <div v-for="field in quotaFields" :key="field.key">
              <label class="block text-xs mb-1" style="color: var(--geek-text-muted)">{{ field.label }}（{{ field.unit }}）</label>
              <input
                v-model="quotaForm[field.key]"
                type="number"
                min="0"
                step="1"
                :placeholder="quotaDefaults ? `兜底 ${quotaDefaults[field.key] ?? '未设'}` : '兜底值'"
                class="geek-input w-full px-3 py-1.5 rounded-md text-sm tabular-nums"
              />
            </div>
          </div>
          <p class="text-xs mt-2" style="color: var(--geek-text-muted)">
            留空表示该项不修改（新建时后端先用兜底值补齐，避免配置行留空值）；填 0 表示直接封禁该维度。
          </p>
          <div class="flex items-center gap-3 mt-3">
            <button
              @click="onSaveQuota"
              :disabled="quotaSaving"
              class="geek-btn geek-btn-primary geek-btn-sm"
              :class="{ 'opacity-40 cursor-not-allowed': quotaSaving }"
            >
              {{ quotaSaving ? '保存中…' : (quotaEditingId ? '保存修改' : '创建配置') }}
            </button>
            <button v-if="quotaEditingId" @click="resetQuotaForm" class="geek-btn geek-btn-ghost geek-btn-sm">取消编辑</button>
            <span v-if="quotaError" class="text-xs" style="color: var(--geek-error)">{{ quotaError }}</span>
            <span v-else-if="quotaSaved" class="text-xs" style="color: var(--geek-success)">已保存并生效（次日零点重置日维度用量）</span>
          </div>
        </div>
      </section>

      <!-- 邀请码面板（v2.37 邀请码制注册） -->
      <section v-else-if="activeTab === 'invites'" class="flex flex-col gap-4">
        <div class="geek-card rounded-xl p-4">
          <div class="text-sm font-medium mb-2" style="color: var(--geek-text)">批量生成邀请码</div>
          <p class="text-xs leading-relaxed mb-3" style="color: var(--geek-text-secondary)">
            一个码只能注册一个账号，注册成功即作废且<b>不退还</b>（注册后的删除、封禁都不会回补码量）。
            码值仅在生成时返回这一次，服务端不保留可回看的副本，请当场转发给新人。
          </p>
          <div class="flex items-end gap-3">
            <div class="w-32">
              <label class="block text-xs mb-1" style="color: var(--geek-text-muted)">数量（1~{{ MAX_INVITE_CODES_PER_REQUEST }}）</label>
              <input
                v-model="inviteCount"
                type="number"
                min="1"
                :max="MAX_INVITE_CODES_PER_REQUEST"
                class="geek-input w-full px-3 py-1.5 rounded-md text-sm tabular-nums"
              />
            </div>
            <button
              @click="onGenerateInvites"
              :disabled="inviteGenerating"
              class="geek-btn geek-btn-primary geek-btn-sm"
              :class="{ 'opacity-40 cursor-not-allowed': inviteGenerating }"
            >
              {{ inviteGenerating ? '生成中…' : '生成' }}
            </button>
            <button
              v-if="generatedCodes.length"
              @click="copyGenerated"
              class="geek-btn geek-btn-ghost geek-btn-sm"
            >
              复制本次生成的码
            </button>
          </div>
          <p v-if="inviteError" class="text-xs mt-2" style="color: var(--geek-error)">{{ inviteError }}</p>
          <p v-else-if="inviteCopied" class="text-xs mt-2" style="color: var(--geek-success)">已复制到剪贴板</p>
          <textarea
            v-if="generatedCodes.length"
            ref="generatedBox"
            readonly
            :value="generatedCodes.join('\n')"
            rows="4"
            class="geek-input w-full mt-3 px-3 py-2 rounded-md text-xs mono"
            style="resize: vertical"
          />
          <p class="text-xs leading-relaxed mt-3" style="color: var(--geek-warning)">
            本面板需要已登录的管理员，而邀请码模式下注册也要码 —— 库里的<b>第一个码</b>只能按手册 5.10 直接
            INSERT，或临时把 REGISTRATION_MODE 设为 open 注册管理员后再切回 invite。
          </p>
        </div>

        <div class="geek-card rounded-xl overflow-hidden">
          <div class="px-4 py-3 border-b geek-divider text-sm font-medium" style="color: var(--geek-text)">
            邀请码台账（未使用的排前面，共 {{ inviteTotal }} 个）
          </div>
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b geek-divider" style="background: var(--geek-bg-subtle)">
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">邀请码</th>
                <th class="text-center px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">状态</th>
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">使用人</th>
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">生成人</th>
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">生成时间</th>
                <th class="text-left px-4 py-2.5 font-medium" style="color: var(--geek-text-secondary)">使用时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in inviteCodes" :key="row.id" class="border-b geek-divider">
                <td class="px-4 py-2.5 mono text-xs" style="color: var(--geek-text)">{{ row.code }}</td>
                <td class="px-4 py-2.5 text-center">
                  <span
                    class="text-xs px-1.5 py-0.5 rounded-sm" :style="row.usedBy
                      ? { background: 'var(--geek-input-bg)', color: 'var(--geek-text-muted)' }
                      : { background: 'var(--geek-success-bg)', color: 'var(--geek-success)' }"
                  >
                    {{ row.usedBy ? '已使用' : '未使用' }}
                  </span>
                </td>
                <td class="px-4 py-2.5 mono text-xs" style="color: var(--geek-text-secondary)" :title="row.usedBy || ''">{{ shortId(row.usedBy) }}</td>
                <td class="px-4 py-2.5 mono text-xs" style="color: var(--geek-text-secondary)" :title="row.createdBy || ''">{{ shortId(row.createdBy) }}</td>
                <td class="px-4 py-2.5 text-xs" style="color: var(--geek-text-muted)">{{ formatTime(row.createdAt) }}</td>
                <td class="px-4 py-2.5 text-xs" style="color: var(--geek-text-muted)">{{ formatTime(row.usedAt) }}</td>
              </tr>
              <tr v-if="inviteCodes.length === 0">
                <td colspan="6" class="px-4 py-10 text-center text-sm" style="color: var(--geek-text-muted)">
                  尚无邀请码：邀请码模式下注册会被全部拒回，先用上方「生成」建第一批
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="inviteTotal > PAGE_SIZE" class="flex items-center justify-between px-4 py-3 border-t geek-divider">
            <span class="text-xs" style="color: var(--geek-text-muted)">共 {{ inviteTotal }} 条</span>
            <div class="flex items-center gap-2">
              <button @click="prevInvitePage" :disabled="invitePage <= 1" class="geek-btn geek-btn-ghost geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': invitePage <= 1 }">上一页</button>
              <span class="text-xs" style="color: var(--geek-text-muted)">{{ invitePage }} / {{ inviteTotalPages }}</span>
              <button @click="nextInvitePage" :disabled="invitePage >= inviteTotalPages" class="geek-btn geek-btn-ghost geek-btn-sm" :class="{ 'opacity-40 cursor-not-allowed': invitePage >= inviteTotalPages }">下一页</button>
            </div>
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
import { useNotification } from '../composables/useNotification'
import { useTheme } from '../composables/useTheme'
import { fetchOverview, fetchAuditLogs, fetchUsers, fetchArchiveOverview, runArchive, fetchQuotas, fetchQuotaDefaults, upsertQuota, fetchInviteCodes, generateInviteCodes, MAX_INVITE_CODES_PER_REQUEST, type AdminOverview, type AdminAuditLog, type ArchiveOverview, type ArchiveRunResult, type AdminQuota, type QuotaUpsertPayload, type AdminInviteCode } from '../api/admin'
import type { User } from '../types'

const { themeMode, setTheme } = useTheme()
const { show } = useNotification()
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
const activeTab = ref<'audit' | 'users' | 'archive' | 'quotas' | 'invites'>('audit')

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
    show('数据归档概览加载失败，页面显示的归档状态不是最新', 'error')
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

// 配额管理
const QUOTA_FIELDS = [
  { key: 'assistantLimit', label: '助手数量', unit: '个' },
  { key: 'dailyCallLimit', label: '单日通话次数', unit: '次' },
  { key: 'dailyCallSecLimit', label: '单日通话时长', unit: '秒' },
  { key: 'dailyMsgLimit', label: '单日消息量', unit: '条' },
] as const

type QuotaFieldKey = (typeof QUOTA_FIELDS)[number]['key']
type QuotaFormModel = { scopeType: 'user' | 'org'; scopeId: string } & Record<QuotaFieldKey, string>

const quotaFields = QUOTA_FIELDS
const emptyQuotaForm = (): QuotaFormModel => ({
  scopeType: 'user',
  scopeId: '',
  assistantLimit: '',
  dailyCallLimit: '',
  dailyCallSecLimit: '',
  dailyMsgLimit: '',
})

const quotas = ref<AdminQuota[]>([])
const quotaDefaults = ref<AdminQuota | null>(null)
const quotaUserOptions = ref<User[]>([])
const quotaForm = ref<QuotaFormModel>(emptyQuotaForm())
const quotaEditingId = ref<string | null>(null)
const quotaSaving = ref(false)
const quotaError = ref('')
const quotaSaved = ref(false)

/** 配置行 + 兜底值 + 用户下拉一起取；用户仅取前 200 条（小范围试验量级足够，组织按 UUID 手填） */
const loadQuotas = async () => {
  try {
    const [list, defaults, userPage] = await Promise.all([
      fetchQuotas(),
      fetchQuotaDefaults(),
      fetchUsers(1, 200),
    ])
    quotas.value = list
    quotaDefaults.value = defaults
    quotaUserOptions.value = userPage.list
  } catch (error) {
    console.error('加载配额配置失败:', error)
    show('配额配置加载失败，请先重试再修改，避免覆盖不到位的配置', 'error')
  }
}

const resetQuotaForm = () => {
  quotaForm.value = emptyQuotaForm()
  quotaEditingId.value = null
  quotaError.value = ''
  quotaSaved.value = false
}

const startEditQuota = (q: AdminQuota) => {
  quotaEditingId.value = `${q.scopeType}:${q.scopeId}`
  quotaForm.value = {
    scopeType: q.scopeType === 'org' ? 'org' : 'user',
    scopeId: q.scopeId,
    assistantLimit: q.assistantLimit == null ? '' : String(q.assistantLimit),
    dailyCallLimit: q.dailyCallLimit == null ? '' : String(q.dailyCallLimit),
    dailyCallSecLimit: q.dailyCallSecLimit == null ? '' : String(q.dailyCallSecLimit),
    dailyMsgLimit: q.dailyMsgLimit == null ? '' : String(q.dailyMsgLimit),
  }
  quotaError.value = ''
  quotaSaved.value = false
}

const onSaveQuota = async () => {
  quotaError.value = ''
  quotaSaved.value = false
  const scopeType = quotaForm.value.scopeType
  const scopeId = quotaForm.value.scopeId.trim()
  if (!scopeId) {
    quotaError.value = scopeType === 'org' ? '请填写组织 ID' : '请选择用户'
    return
  }
  const payload: QuotaUpsertPayload = { scopeType, scopeId }
  for (const field of quotaFields) {
    const raw = quotaForm.value[field.key].trim()
    if (raw === '') continue // 留空 = 不修改该维度（后端 UPSERT 忽略缺省字段）
    const n = Number(raw)
    if (!Number.isInteger(n) || n < 0) {
      quotaError.value = `${field.label}需为不小于 0 的整数`
      return
    }
    payload[field.key] = n
  }
  quotaSaving.value = true
  try {
    await upsertQuota(payload)
    quotaSaved.value = true
    quotaEditingId.value = `${scopeType}:${scopeId}`
    await loadQuotas()
  } catch (error) {
    quotaError.value = error instanceof Error ? error.message : '配额保存失败'
  } finally {
    quotaSaving.value = false
  }
}

const scopeLabel = (q: AdminQuota) => {
  if (q.scopeType === 'user') {
    const matched = quotaUserOptions.value.find(u => u.id === q.scopeId)
    if (matched) return `${matched.username}（${matched.nickname || '无昵称'}）`
  }
  return `${q.scopeId.slice(0, 8)}…`
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
    show(`审计日志加载失败：${(error as Error).message}`, 'error')
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
    show(`用户列表加载失败：${(error as Error).message}`, 'error')
  }
}
const onSearchUser = () => { userPage.value = 1; loadUsers() }
const prevUserPage = () => { if (userPage.value > 1) { userPage.value--; loadUsers() } }
const nextUserPage = () => { if (userPage.value < userTotalPages.value) { userPage.value++; loadUsers() } }

// 邀请码台账（v2.37）
const inviteCodes = ref<AdminInviteCode[]>([])
const invitePage = ref(1)
const inviteTotal = ref(0)
const inviteTotalPages = computed(() => Math.max(1, Math.ceil(inviteTotal.value / PAGE_SIZE)))
const inviteCount = ref('5')
const inviteGenerating = ref(false)
const inviteError = ref('')
const inviteCopied = ref(false)
const generatedCodes = ref<string[]>([])
const generatedBox = ref<HTMLTextAreaElement | null>(null)

const loadInviteCodes = async () => {
  try {
    const result = await fetchInviteCodes(invitePage.value, PAGE_SIZE)
    inviteCodes.value = result.list
    inviteTotal.value = result.total
  } catch (error) {
    console.error('加载邀请码列表失败:', error)
    show(`邀请码台账加载失败：${(error as Error).message}`, 'error')
  }
}
const prevInvitePage = () => { if (invitePage.value > 1) { invitePage.value--; loadInviteCodes() } }
const nextInvitePage = () => { if (invitePage.value < inviteTotalPages.value) { invitePage.value++; loadInviteCodes() } }

const onGenerateInvites = async () => {
  if (inviteGenerating.value) return
  inviteError.value = ''
  inviteCopied.value = false
  const n = Number(inviteCount.value.trim())
  // 上限交给服务端兜底判定，这里只挡明显无效值，避免把注定 400 的请求发出去
  if (!Number.isInteger(n) || n < 1 || n > MAX_INVITE_CODES_PER_REQUEST) {
    inviteError.value = `数量需为 1~${MAX_INVITE_CODES_PER_REQUEST} 的整数`
    return
  }
  inviteGenerating.value = true
  try {
    const result = await generateInviteCodes(n)
    generatedCodes.value = result.codes
    invitePage.value = 1
    await loadInviteCodes()
  } catch (error) {
    inviteError.value = error instanceof Error ? error.message : '邀请码生成失败'
  } finally {
    inviteGenerating.value = false
  }
}

/** 复制本次生成的码；非安全上下文（http 访问）没有 clipboard API，退化为选中文本让用户手动复制 */
const copyGenerated = async () => {
  const text = generatedCodes.value.join('\n')
  try {
    await navigator.clipboard.writeText(text)
    inviteCopied.value = true
  } catch {
    inviteCopied.value = false
    generatedBox.value?.select()
  }
}

/** 台账里的用户列只放前缀：UUID 全展开会把表格挤成横滚，完整值在 title 上 */
const shortId = (value?: string | null) => (value ? `${value.slice(0, 8)}…` : '-')

const formatTime = (value?: string | null) => {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(async () => {
  await Promise.all([
    fetchOverview()
      .then(data => { overview.value = data })
      .catch(e => {
        console.error('加载概览失败:', e)
        show('概览统计加载失败，卡片数字可能为空', 'error')
      }),
    loadAuditLogs(),
    loadUsers(),
    loadArchiveOverview(),
    loadQuotas(),
    loadInviteCodes(),
  ])
})
</script>