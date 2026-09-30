<template>
  <div class="geek-body theme-transition h-screen w-full flex overflow-hidden antialiased">
    <!-- 左小半：品牌 + 导航 + 本页自己的列表 + 用户区 -->
    <aside class="sidebar w-72 flex-shrink-0 flex flex-col bg-geek-surface border-r border-geek">
      <div class="brand-header px-6 py-5 border-b border-geek">
        <h1 class="font-display text-xl font-bold tracking-tight text-geek-text">云谕助手</h1>
        <p class="text-xs mt-1 mono tracking-widest text-geek-text-muted">WORKSPACE // {{ subtitle }}</p>
      </div>

      <NavList />

      <div class="flex-1 min-h-0 overflow-y-auto geek-scroll transparent-scrollbar px-3 pb-4">
        <slot name="list" />
      </div>

      <div class="user-bar px-4 py-3 border-t border-geek">
        <div class="flex items-center justify-between">
          <div class="user-info flex items-center gap-2 min-w-0">
            <div class="user-avatar w-7 h-7 rounded-md flex items-center justify-center text-xs font-medium text-white bg-geek-primary flex-shrink-0">
              {{ userInitial }}
            </div>
            <span class="text-sm text-geek-text truncate">{{ userName }}</span>
          </div>
          <button
            @click="handleLogout"
            class="geek-btn geek-btn-ghost text-xs px-2 py-1 flex items-center gap-1 flex-shrink-0"
          >
            <LogOut class="w-3.5 h-3.5" />
            <span>登出</span>
          </button>
        </div>
      </div>
    </aside>

    <!-- 右大半：内容由页面自己排布（工作台要保持消息区滚动 + 输入区固定，壳不接管） -->
    <main class="main-panel flex-1 flex flex-col min-w-0 bg-geek-base">
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { LogOut } from 'lucide-vue-next'
import NavList from './NavList.vue'
import { logout, clearSession } from '../api/auth'

withDefaults(defineProps<{ subtitle?: string }>(), { subtitle: '助手工作台' })

const router = useRouter()

const userName = typeof localStorage !== 'undefined'
  ? (localStorage.getItem('username') || '用户')
  : '用户'
const userInitial = userName.charAt(0).toUpperCase()

// 正在通话时视图仍在本页挂载，切路由即触发其 onBeforeUnmount 关闭 ws / voiceWs / WebRTC
const handleLogout = () => {
  const refreshToken = localStorage.getItem('refreshToken') || undefined
  logout(refreshToken).catch(() => {
    // 网络异常不阻塞本地登出
  })
  clearSession()
  router.push('/login')
}
</script>
