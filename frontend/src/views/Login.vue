<template>
  <div
    class="h-screen w-full flex items-center justify-center bg-gradient-to-br from-blue-300 via-pink-300 to-yellow-200 bg-[length:400%_400%] animate-gradient-diagonal relative overflow-hidden antialiased">
    <div class="w-full max-w-md mx-4">
      <div class="text-center mb-8">
        <h1 class="text-5xl font-bold text-white drop-shadow-lg mb-3">云谕助手</h1>
        <p class="text-lg text-white/80">Create. Explore. Together.</p>
      </div>

      <div class="bg-white/10 backdrop-blur-xl rounded-3xl shadow-2xl ring-1 ring-white/20 p-8">
        <h2 class="text-2xl font-bold text-white text-center mb-8">登录</h2>

        <div class="space-y-5">
          <div class="relative">
            <User class="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-white/50" />
            <input v-model="form.name" type="text" placeholder="用户名"
              class="w-full pl-12 pr-4 py-3.5 rounded-xl border-0 bg-white/10 text-white placeholder:text-white/50 focus:outline-none focus:ring-2 focus:ring-white/50 text-lg transition"
              @keyup.enter="handleLogin" />
          </div>

          <div class="relative">
            <Lock class="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-white/50" />
            <input v-model="form.password" type="password" placeholder="密码"
              class="w-full pl-12 pr-4 py-3.5 rounded-xl border-0 bg-white/10 text-white placeholder:text-white/50 focus:outline-none focus:ring-2 focus:ring-white/50 text-lg transition"
              @keyup.enter="handleLogin" />
          </div>
        </div>

        <div v-if="errorMsg" class="mt-4 text-red-300 text-sm text-center bg-red-500/20 rounded-lg py-2 px-3">
          {{ errorMsg }}
        </div>

        <button @click="handleLogin" :disabled="loading || !canSubmit" :class="[
          'w-full mt-6 py-3.5 rounded-xl font-semibold text-lg transition-all duration-300 transform active:scale-[0.98]',
          (loading || !canSubmit)
            ? 'bg-white/20 text-white/50 cursor-not-allowed'
            : 'bg-blue-500/80 text-white hover:bg-blue-500 shadow-lg ring-1 ring-blue-400/30'
        ]">
          <span v-if="loading" class="flex items-center justify-center">
            <svg class="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none"
              viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z">
              </path>
            </svg>
            登录中...
          </span>
          <span v-else>登录</span>
        </button>

        <div class="mt-6 text-center">
          <span class="text-white/60">还没有账号？</span>
          <router-link to="/register"
            class="text-blue-300 hover:text-blue-200 font-medium transition-colors ml-1">
            注册
          </router-link>
        </div>
      </div>

      <div class="mt-6 bg-amber-400/20 backdrop-blur-lg rounded-2xl ring-2 ring-amber-400/50 p-5 shadow-lg">
        <div class="flex items-center gap-2 mb-3">
          <div class="w-7 h-7 bg-amber-400/30 rounded-lg flex items-center justify-center">
            <svg class="w-4 h-4 text-amber-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </div>
          <span class="text-amber-200 font-bold text-base">体验账号</span>
        </div>
        <div class="space-y-1.5">
          <div class="flex items-center gap-3">
            <span class="text-white/70 text-sm w-12 shrink-0">用户名</span>
            <code class="bg-white/15 text-amber-100 px-3 py-1 rounded-lg text-base font-mono font-bold tracking-wider select-all">demo</code>
          </div>
          <div class="flex items-center gap-3">
            <span class="text-white/70 text-sm w-12 shrink-0">密码</span>
            <code class="bg-white/15 text-amber-100 px-3 py-1 rounded-lg text-base font-mono font-bold tracking-wider select-all">demo123</code>
          </div>
        </div>
        <p class="text-amber-200/70 text-xs mt-3">使用体验账号可直接登录，已预置3个智能助手</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from 'lucide-vue-next'
import { login } from '../api/auth'

const router = useRouter()

const form = ref({
  name: '',
  password: '',
})

const loading = ref(false)
const errorMsg = ref('')

const canSubmit = computed(() => form.value.name.trim() && form.value.password.trim())

const handleLogin = async () => {
  if (!canSubmit.value || loading.value) return

  loading.value = true
  errorMsg.value = ''

  try {
    const res = await login({
      name: form.value.name.trim(),
      password: form.value.password,
    })
    localStorage.setItem('token', res.token)
    localStorage.setItem('userId', res.userId)
    localStorage.setItem('username', res.username)
    router.push('/smartrobot')
  } catch (error) {
    errorMsg.value = (error as Error).message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>
