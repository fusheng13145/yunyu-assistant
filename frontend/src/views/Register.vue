<template>
  <div class="register-page geek-body geek-grid-bg">
    <!-- 主题切换 -->
    <div class="theme-toggle-wrap">
      <ThemeToggle v-model="themeMode" />
    </div>

    <div class="register-container">
      <!-- 品牌区域 -->
      <header class="brand-header">
        <p class="brand-kicker mono">YUNYU ASSISTANT // REGISTER</p>
        <h1 class="brand-title font-display">云谕助手</h1>
        <p class="brand-subtitle">智能协作，静谧致远</p>
      </header>

      <!-- 注册表单卡片 -->
      <main class="geek-card form-card animate-fade-up">
        <h2 class="form-title">创建账号</h2>

        <form @submit.prevent="handleRegister" class="register-form">
          <div class="form-group">
            <label class="form-label">用户名</label>
            <input
              v-model="form.username"
              type="text"
              placeholder="请输入用户名"
              class="geek-input"
              @keyup.enter="handleRegister"
            />
          </div>

          <div class="form-group">
            <label class="form-label">密码</label>
            <div class="password-input-wrap">
              <input
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入密码（至少6位）"
                class="geek-input"
                @keyup.enter="handleRegister"
              />
              <button
                type="button"
                class="password-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <EyeOff v-if="showPassword" class="w-4 h-4" />
                <Eye v-else class="w-4 h-4" />
              </button>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label">确认密码</label>
            <div class="password-input-wrap">
              <input
                v-model="form.confirmPassword"
                :type="showConfirmPassword ? 'text' : 'password'"
                placeholder="请再次输入密码"
                class="geek-input"
                @keyup.enter="handleRegister"
              />
              <button
                type="button"
                class="password-toggle"
                :aria-label="showConfirmPassword ? '隐藏密码' : '显示密码'"
                @click="showConfirmPassword = !showConfirmPassword"
              >
                <EyeOff v-if="showConfirmPassword" class="w-4 h-4" />
                <Eye v-else class="w-4 h-4" />
              </button>
            </div>
          </div>

          <div v-if="inviteRequired" class="form-group">
            <label class="form-label">邀请码</label>
            <input
              v-model="form.inviteCode"
              type="text"
              placeholder="请输入管理员发放的一次性邀请码"
              class="geek-input invite-code-input"
              autocomplete="one-time-code"
              @keyup.enter="handleRegister"
            />
          </div>

          <!-- 填写规则提示 -->
          <div class="rule-hint">
            <svg class="rule-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9 12l2 2 4-4"></path>
              <circle cx="12" cy="12" r="10"></circle>
            </svg>
            <span>
              用户名唯一，密码至少6位。注册成功后将自动登录。
              <template v-if="inviteRequired">邀请码用后即废，一个码只能注册一个账号。</template>
            </span>
          </div>

          <!-- 错误提示 -->
          <div v-if="errorMsg" class="error-msg">
            {{ errorMsg }}
          </div>

          <!-- 注册按钮 -->
          <button
            type="button"
            @click="handleRegister"
            :disabled="loading || !canSubmit"
            class="geek-btn geek-btn-primary submit-btn"
          >
            <span v-if="loading" class="btn-loading">
              <svg class="spin-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              注册中...
            </span>
            <span v-else>注册</span>
          </button>

          <!-- 登录跳转 -->
          <div class="form-footer">
            <span class="footer-hint">已有账号？</span>
            <router-link to="/login" class="footer-link">返回登录</router-link>
          </div>
        </form>
      </main>

      <!-- 体验账号提示卡片 -->
      <aside class="geek-card info-tip animate-fade-up">
        <div class="tip-inner">
          <svg class="tip-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="16" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12.01" y2="8"></line>
          </svg>
          <div class="tip-content">
            <p class="tip-title">{{ inviteRequired ? '邀请码注册' : '开放注册' }}</p>
            <p class="tip-desc">
              {{ inviteRequired
                ? '当前需向管理员索取一次性邀请码才能注册；已注册账号仍可直接登录。'
                : '当前可直接注册，无需邀请码。' }}
            </p>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Eye, EyeOff } from 'lucide-vue-next'
import { useTheme } from '../composables/useTheme'
import ThemeToggle from '../components/ThemeToggle.vue'
import { register, saveSession, fetchRegisterConfig } from '../api/auth'

const router = useRouter()
const { themeMode } = useTheme()

// 密码可见性
const showPassword = ref(false)
const showConfirmPassword = ref(false)

/**
 * 是否需要邀请码：默认按更严格的一侧显示输入框。
 * 拉取失败的代价是"多填一个没用的框"，反过来则是"邀请码模式下用户不知道该填什么"。
 */
const inviteRequired = ref(true)

// 注册表单数据
const form = ref({
  username: '',
  password: '',
  confirmPassword: '',
  inviteCode: '',
})

// 状态标识
const loading = ref(false)
const errorMsg = ref('')

onMounted(async () => {
  try {
    inviteRequired.value = (await fetchRegisterConfig()).inviteRequired
  } catch {
    // 查询失败保持默认（要求邀请码）：服务端仍是最终裁判，表单只是少一次提示
  }
})

// 表单校验：必填项齐全才可提交（开放注册模式下邀请码不参与校验）
const canSubmit = computed(() => {
  return form.value.username.trim() &&
    form.value.password.trim() &&
    form.value.confirmPassword.trim() &&
    (!inviteRequired.value || form.value.inviteCode.trim())
})

/**
 * 注册提交逻辑
 */
const handleRegister = async () => {
  if (!canSubmit.value || loading.value) return

  // 密码一致性校验
  if (form.value.password !== form.value.confirmPassword) {
    errorMsg.value = '两次输入的密码不一致，请重新填写'
    return
  }
  // 密码长度校验
  if (form.value.password.length < 6) {
    errorMsg.value = '密码长度不能少于6位，请调整'
    return
  }

  loading.value = true
  errorMsg.value = ''

  try {
    const res = await register({
      username: form.value.username.trim(),
      password: form.value.password,
      // 大小写不敏感在服务端归一，这里只去首尾空白
      inviteCode: form.value.inviteCode.trim() || undefined,
    })
    // 存储登录信息（含刷新令牌与角色）
    saveSession(res)
    // 注册成功跳转首页
    router.push('/smartrobot')
  } catch (err) {
    errorMsg.value = (err as Error).message || '账号注册失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 页面根容器：纯白基底 */
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--geek-bg-base);
  position: relative;
  overflow: hidden;
  transition: background-color 0.3s ease;
}

/* 主题切换定位 */
.theme-toggle-wrap {
  position: absolute;
  top: 20px;
  right: 24px;
  z-index: 10;
}

/* 内容容器 */
.register-container {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 400px;
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}

/* 品牌头部 */
.brand-header {
  text-align: center;
  margin-bottom: 4px;
}

/* 等宽技术标签 */
.brand-kicker {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.14em;
  color: var(--geek-accent);
  margin: 0 0 10px;
  text-transform: uppercase;
}

.brand-title {
  font-size: 32px;
  font-weight: 800;
  color: var(--geek-text);
  letter-spacing: -0.02em;
  line-height: 1.15;
  margin: 0;
}

.brand-subtitle {
  margin-top: 8px;
  font-size: 14px;
  color: var(--geek-text-muted);
  letter-spacing: 0.04em;
  margin: 0;
}

/* 表单卡片 */
.form-card {
  width: 100%;
  padding: 32px 28px 24px;
  border-radius: var(--radius-lg);
}

.form-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--geek-text);
  text-align: center;
  margin: 0 0 22px;
  letter-spacing: -0.01em;
}

/* 表单布局 */
.register-form {
  display: flex;
  flex-direction: column;
}

.form-group {
  margin-bottom: 14px;
}

.form-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--geek-text-secondary);
  margin-bottom: 6px;
  letter-spacing: 0.02em;
}

.form-group .geek-input {
  width: 100%;
  height: 42px;
  padding: 0 14px;
  font-size: 14px;
  border-radius: var(--radius-md);
  box-sizing: border-box;
}

/* 邀请码：服务端生成的码全为大写，此处同步显示为大写以免看起来"输错了" */
.invite-code-input {
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

/* 密码可见性切换 */
.password-input-wrap {
  position: relative;
}
.password-input-wrap .geek-input {
  width: 100%;
  padding-right: 40px;
}
.password-toggle {
  position: absolute;
  top: 50%;
  right: 12px;
  transform: translateY(-50%);
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--geek-text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2px;
  transition: color 0.15s ease;
}
.password-toggle:hover {
  color: var(--geek-text);
}

/* 规则提示栏 */
.rule-hint {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 10px 12px;
  margin: 2px 0 4px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--geek-text-muted);
  background: var(--geek-bg-subtle);
  border-radius: var(--radius-sm);
  border: 1px solid var(--geek-border);
}

.rule-icon {
  width: 15px;
  height: 15px;
  min-width: 15px;
  color: var(--geek-accent);
  margin-top: 1px;
}

/* 错误提示：左侧色条 */
.error-msg {
  margin-top: 4px;
  padding: 10px 14px;
  font-size: 13px;
  border-radius: var(--radius-sm);
  background: var(--geek-error-bg);
  color: var(--geek-error);
  border-left: 3px solid var(--geek-error);
  line-height: 1.5;
}

/* 注册按钮 */
.submit-btn {
  width: 100%;
  height: 44px;
  margin-top: 8px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.02em;
  border-radius: var(--radius-md);
  cursor: pointer;
}

.btn-loading {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.spin-icon {
  width: 16px;
  height: 16px;
  animation: spin 0.8s linear infinite;
  color: var(--geek-text-on-primary);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 底部跳转链接 */
.form-footer {
  margin-top: 20px;
  text-align: center;
  font-size: 13px;
}

.footer-hint {
  color: var(--geek-text-muted);
}

.footer-link {
  color: var(--geek-accent);
  font-weight: 600;
  margin-left: 4px;
  transition: color 0.15s ease;
}

.footer-link:hover {
  color: var(--geek-accent-hover);
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* 体验账号提示卡片 */
.info-tip {
  width: 100%;
  padding: 14px 16px;
  border-radius: var(--radius-md);
}

.tip-inner {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.tip-icon {
  width: 18px;
  height: 18px;
  min-width: 18px;
  color: var(--geek-accent);
  margin-top: 1px;
}

.tip-content {
  flex: 1;
}

.tip-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--geek-text-secondary);
  margin: 0 0 3px;
}

.tip-desc {
  font-size: 12px;
  color: var(--geek-text-muted);
  line-height: 1.6;
  margin: 0;
}

/* 全局淡入动画（与登录页、404页统一） */
.animate-fade-up {
  animation: fadeUp 0.4s ease-out;
}

@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>