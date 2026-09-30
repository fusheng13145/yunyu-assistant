<template>
  <nav class="px-4 pt-4 pb-2 flex flex-col gap-2" aria-label="站点导航">
    <button
      v-for="item in visibleItems"
      :key="item.path"
      type="button"
      class="geek-listrow"
      :class="{ 'geek-listrow--active': route.path === item.path }"
      :aria-current="route.path === item.path ? 'page' : undefined"
      @click="go(item.path)"
    >
      <component :is="item.icon" class="w-4 h-4 flex-shrink-0" />
      <span>{{ item.label }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MessageSquare, History, Gauge, Users, KeyRound, Shield } from 'lucide-vue-next'

const NAV_ITEMS = [
  { path: '/smartrobot', label: '对话工作台', icon: MessageSquare, adminOnly: false },
  { path: '/records', label: '通话记录', icon: History, adminOnly: false },
  { path: '/billing', label: '用量配额', icon: Gauge, adminOnly: false },
  { path: '/org', label: '组织管理', icon: Users, adminOnly: false },
  { path: '/apps', label: '应用管理', icon: KeyRound, adminOnly: false },
  { path: '/admin', label: '管理后台', icon: Shield, adminOnly: true },
]

const route = useRoute()
const router = useRouter()

// 与路由守卫同源（守卫读的是同一个 localStorage.role），这里只决定"看得见"，不是"进得去"
const isAdmin = typeof localStorage !== 'undefined'
  ? (localStorage.getItem('role') || 'user') === 'admin'
  : false

const visibleItems = computed(() => (isAdmin ? NAV_ITEMS : NAV_ITEMS.filter(i => !i.adminOnly)))

const go = (path: string) => {
  if (route.path !== path) router.push(path)
}
</script>
