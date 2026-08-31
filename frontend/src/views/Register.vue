<template>
  <div class="register-page morandi-body">
    <!-- 主题切换 -->
    <div class="theme-toggle-wrap">
      <ThemeToggle v-model="themeMode" />
    </div>

    <div class="register-container">
      <!-- 品牌区域 -->
      <header class="brand-header">
        <h1 class="brand-title serif">云谕助手</h1>
        <p class="brand-subtitle">智能协作，静谧致远</p>
      </header>

      <!-- 注册表单卡片 -->
      <main class="morandi-card form-card animate-fade-up">
        <h2 class="form-title">创建账号</h2>

        <form @submit.prevent="handleRegister" class="register-form">
          <div class="form-group">
            <label class="form-label">用户名</label>
            <input
              v-model="form.username"
              type="text"
              placeholder="请输入用户名"
              class="morandi-input"
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
                class="morandi-input"
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
                class="morandi-input"
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

          <!-- 填写规则提示 -->
          <div class="rule-hint">
            <svg class="rule-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9 12l2 2 4-4"></path>
              <circle cx="12" cy="12" r="10"></circle>
            </svg>
            <span>用户名唯一，密码至少6位。注册成功后将自动登录。</span>
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
            class="morandi-btn morandi-btn-primary submit-btn"
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
      <aside class="morandi-card info-tip animate-fade-up">
        <div class="tip-inner">
          <svg class="tip-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="16" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12.01" y2="8"></line>
          </svg>
          <div class="tip-content">
            <p class="tip-title">体验账号</p>
            <p class="tip-desc">联系管理员获取体验账号，也可使用上方表单注册新账号。</p>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Eye, EyeOff } from 'lucide-vue-next'
import { useTheme } from '../composables/useTheme'
import ThemeToggle from '../components/ThemeToggle.vue'
import { register } from '../api/auth'

const router = useRouter()
const { themeMode } = useTheme()

// 密码可见性
const showPassword = ref(false)
const showConfirmPassword = ref(false)

// 注册表单数据
const form = ref({
  username: '',
  password: '',
  confirmPassword: '',
})

// 状态标识
const loading = ref(false)
const errorMsg = ref('')

// 表单校验：全部输入不为空才可提交
const canSubmit = computed(() => {
  return form.value.username.trim() &&
    form.value.password.trim() &&
    form.value.confirmPassword.trim()
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
    })
    // 存储登录信息
    localStorage.setItem('token', res.token)
    localStorage.setItem('userId', res.userId)
    localStorage.setItem('username', res.username)
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
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--morandi-bg-base);
  position: relative;
  overflow: hidden;
  transition: background-color 0.3s ease;
}

/* 背景渐变装饰 */
.register-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    145deg,
    var(--morandi-bg-warm) 0%,
    var(--morandi-bg-base) 40%,
    var(--morandi-bg-cool) 100%
  );
  opacity: 0.6;
  z-index: 0;
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
  max-width: 420px;
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

.brand-title {
  font-size: 30px;
  font-weight: 600;
  color: var(--morandi-text);
  letter-spacing: 0.04em;
  line-height: 1.3;
  margin: 0;
}

.brand-subtitle {
  margin-top: 8px;
  font-size: 14px;
  color: var(--morandi-text-muted);
  letter-spacing: 0.06em;
  margin: 0;
}

/* 表单卡片 */
.form-card {
  width: 100%;
  padding: 36px 32px 28px;
  border-radius: var(--radius-lg);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
}

.form-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--morandi-text);
  text-align: center;
  margin: 0 0 24px;
  letter-spacing: 0.02em;
}

/* 表单布局 */
.register-form {
  display: flex;
  flex-direction: column;
}

.form-group {
  margin-bottom: 16px;
}

.form-label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--morandi-text-secondary);
  margin-bottom: 6px;
  letter-spacing: 0.02em;
}

.form-group .morandi-input {
  width: 100%;
  height: 42px;
  padding: 0 14px;
  font-size: 14px;
  border-radius: var(--radius-md);
  box-sizing: border-box;
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

.form-group .morandi-input:focus {
  box-shadow: 0 0 0 2px rgba(140, 129, 120, 0.15);
}

/* 密码可见性切换 */
.password-input-wrap {
  position: relative;
}
.password-input-wrap .morandi-input {
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
  color: var(--morandi-text-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2px;
}
.password-toggle:hover {
  color: var(--morandi-text-secondary);
}

/* 规则提示栏 */
.rule-hint {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 11px 13px;
  margin: 2px 0 4px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--morandi-text-muted);
  background: var(--morandi-bg-subtle);
  border-radius: var(--radius-sm);
  border: 1px solid var(--morandi-border);
}

.rule-icon {
  width: 15px;
  height: 15px;
  min-width: 15px;
  color: var(--morandi-primary);
  margin-top: 1px;
  opacity: 0.65;
}

/* 错误提示 */
.error-msg {
  margin-top: 4px;
  padding: 10px 14px;
  font-size: 13px;
  text-align: center;
  border-radius: var(--radius-sm);
  background: rgba(211, 84, 84, 0.08);
  color: var(--morandi-error);
  border: 1px solid rgba(211, 84, 84, 0.15);
  line-height: 1.5;
}

/* 注册按钮 */
.submit-btn {
  width: 100%;
  height: 44px;
  margin-top: 8px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.04em;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 0.2s ease;
}

.submit-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  transform: none !important;
  box-shadow: none !important;
}

.submit-btn:not(:disabled):hover {
  transform: translateY(-1px);
  box-shadow: var(--morandi-shadow-md);
}

.submit-btn:not(:disabled):active {
  transform: translateY(0);
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
  color: var(--morandi-text-on-primary);
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
  color: var(--morandi-text-muted);
}

.footer-link {
  color: var(--morandi-primary);
  font-weight: 500;
  margin-left: 4px;
  transition: color 0.2s ease;
}

.footer-link:hover {
  color: var(--morandi-primary-hover);
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* 体验账号提示卡片 */
.info-tip {
  width: 100%;
  padding: 16px 18px;
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
  color: var(--morandi-accent);
  margin-top: 1px;
  opacity: 0.75;
}

.tip-content {
  flex: 1;
}

.tip-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--morandi-text-secondary);
  margin: 0 0 3px;
}

.tip-desc {
  font-size: 12px;
  color: var(--morandi-text-muted);
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