<template>
  <div class="login-page morandi-body">
    <!-- 主题切换按钮 -->
    <div class="theme-toggle-wrap">
      <ThemeToggle v-model="themeMode" />
    </div>

    <div class="login-container">
      <!-- 品牌头部 -->
      <header class="brand-header">
        <h1 class="brand-title serif">云谕助手</h1>
        <p class="brand-subtitle">智能协作，静谧致远</p>
      </header>

      <!-- 登录表单卡片 -->
      <main class="morandi-card form-card animate-fade-up">
        <h2 class="form-title">欢迎回来</h2>

        <form @submit.prevent="handleLogin" class="login-form">
          <div class="form-group">
            <label class="form-label">用户名</label>
            <input
              v-model="form.username"
              type="text"
              placeholder="请输入用户名"
              class="morandi-input"
              @keyup.enter="handleLogin"
            />
          </div>

          <div class="form-group">
            <label class="form-label">密码</label>
            <input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              class="morandi-input"
              @keyup.enter="handleLogin"
            />
          </div>

          <!-- 错误提示 -->
          <div v-if="errorMsg" class="error-msg">
            {{ errorMsg }}
          </div>

          <!-- 登录按钮 -->
          <button
            type="button"
            @click="handleLogin"
            :disabled="loading || !canSubmit"
            class="morandi-btn morandi-btn-primary submit-btn"
          >
            <span v-if="loading" class="btn-loading">
              <svg class="spin-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 5.824 3 7.938l3-2.647z"></path>
              </svg>
              登录中...
            </span>
            <span v-else>登录</span>
          </button>

          <!-- 注册跳转 -->
          <div class="form-footer">
            <span class="footer-hint">还没有账号？</span>
            <router-link to="/register" class="footer-link">注册新账号</router-link>
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
            <p class="tip-desc">联系管理员获取体验账号，也可通过注册功能自主创建新账号。</p>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from '../composables/useTheme'
import ThemeToggle from '../components/ThemeToggle.vue'
import { login } from '../api/auth'

// 路由 & 主题
const router = useRouter()
const { themeMode } = useTheme()

// 登录表单数据
const form = ref({
  username: '',
  password: '',
})

// 状态控制
const loading = ref(false)
const errorMsg = ref('')

// 表单是否可提交
const canSubmit = computed(() => {
  return form.value.username.trim() && form.value.password.trim()
})

/**
 * 登录提交逻辑
 */
const handleLogin = async () => {
  if (!canSubmit.value || loading.value) return

  loading.value = true
  errorMsg.value = ''

  try {
    const res = await login({
      username: form.value.username.trim(),
      password: form.value.password,
    })
    // 本地存储登录信息
    localStorage.setItem('token', res.token)
    localStorage.setItem('userId', res.userId)
    localStorage.setItem('username', res.username)
    // 跳转至助手主页
    router.push('/smartrobot')
  } catch (err) {
    errorMsg.value = (err as Error).message || '登录失败，请检查账号密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 页面根容器 */
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--morandi-bg-base);
  position: relative;
  overflow: hidden;
  transition: background-color 0.3s ease;
}

/* 背景渐变装饰层 */
.login-page::before {
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

/* 登录内容容器 */
.login-container {
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

/* 品牌头部样式 */
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

/* 登录表单卡片 */
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
  margin: 0 0 28px;
  letter-spacing: 0.02em;
}

/* 表单布局 */
.login-form {
  display: flex;
  flex-direction: column;
}

.form-group {
  margin-bottom: 18px;
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

/* 错误提示 */
.error-msg {
  margin: 4px 0 8px;
  padding: 10px 14px;
  font-size: 13px;
  text-align: center;
  border-radius: var(--radius-sm);
  background: rgba(211, 84, 84, 0.08);
  color: var(--morandi-error);
  border: 1px solid rgba(211, 84, 84, 0.15);
  line-height: 1.5;
}

/* 登录按钮 */
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

/* 表单底部跳转 */
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

/* 全局动画类复用 */
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