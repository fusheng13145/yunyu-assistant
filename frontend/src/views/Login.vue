<template>
  <div class="login-page geek-body geek-grid-bg">
    <!-- 主题切换按钮 -->
    <div class="theme-toggle-wrap">
      <ThemeToggle v-model="themeMode" />
    </div>

    <div class="login-container">
      <!-- 品牌头部 -->
      <header class="brand-header">
        <p class="brand-kicker mono">YUNYU ASSISTANT // LOGIN</p>
        <h1 class="brand-title font-display">云谕助手</h1>
        <p class="brand-subtitle">智能协作，静谧致远</p>
      </header>

      <!-- 登录表单卡片 -->
      <main class="geek-card form-card animate-fade-up">
        <h2 class="form-title">欢迎回来</h2>

        <form @submit.prevent="handleLogin" class="login-form">
          <div class="form-group">
            <label class="form-label">用户名</label>
            <input
              v-model="form.username"
              type="text"
              placeholder="请输入用户名"
              class="geek-input"
              @keyup.enter="handleLogin"
            />
          </div>

          <div class="form-group">
            <label class="form-label">密码</label>
            <div class="password-input-wrap">
              <input
                v-model="form.password"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入密码"
                class="geek-input"
                @keyup.enter="handleLogin"
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

          <!-- 错误提示 -->
          <div v-if="errorMsg" class="error-msg">
            {{ errorMsg }}
          </div>

          <!-- 登录按钮 -->
          <button
            type="button"
            @click="handleLogin"
            :disabled="loading || !canSubmit"
            class="geek-btn geek-btn-primary submit-btn"
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
      <aside class="geek-card info-tip animate-fade-up">
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
import { useRoute, useRouter } from 'vue-router'
import { Eye, EyeOff } from 'lucide-vue-next'
import { useTheme } from '../composables/useTheme'
import ThemeToggle from '../components/ThemeToggle.vue'
import { login, saveSession } from '../api/auth'

// 路由 & 主题
const route = useRoute()
const router = useRouter()
const { themeMode } = useTheme()

// 密码可见性
const showPassword = ref(false)

// 登录表单数据
const form = ref({
  username: '',
  password: '',
})

// 状态控制
const loading = ref(false)
const errorMsg = ref('')

// 会话彻底失效时由 api/auth 跳转至此（/login?reason=expired），给一句人话而不是空白表单
if (route.query.reason === 'expired') {
  errorMsg.value = '登录状态已过期，请重新登录'
}

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
    // 本地存储登录信息（含刷新令牌与角色，用于令牌续期与权限控制）
    saveSession(res)
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
/* 页面根容器：纯白基底 */
.login-page {
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

/* 登录内容容器 */
.login-container {
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

/* 品牌头部样式 */
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

/* 登录表单卡片 */
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
  margin: 0 0 24px;
  letter-spacing: -0.01em;
}

/* 表单布局 */
.login-form {
  display: flex;
  flex-direction: column;
}

.form-group {
  margin-bottom: 16px;
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

/* 错误提示：左侧色条 */
.error-msg {
  margin: 4px 0 8px;
  padding: 10px 14px;
  font-size: 13px;
  border-radius: var(--radius-sm);
  background: var(--geek-error-bg);
  color: var(--geek-error);
  border-left: 3px solid var(--geek-error);
  line-height: 1.5;
}

/* 登录按钮 */
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

/* 表单底部跳转 */
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