<template>
  <div class="morandi-body bg-morandi-gradient h-screen w-full flex items-center justify-center relative overflow-hidden antialiased">
    <div class="text-center px-6 animate-fade-up">
      <!-- 装饰细线 -->
      <div class="mx-auto mb-8 w-16 h-px" style="background: var(--morandi-border-strong)"></div>

      <!-- 大号 404 数字 -->
      <h1 class="serif font-bold mb-4 select-none" style="font-size: 136px; line-height: 1; color: var(--morandi-text-muted); letter-spacing: -0.04em">
        404
      </h1>

      <!-- 副标题 -->
      <p class="serif text-2xl font-medium mb-3 tracking-wide" style="color: var(--morandi-text)">页面未找到</p>

      <!-- 说明文字 -->
      <p class="text-sm max-w-sm mx-auto leading-relaxed mb-10" style="color: var(--morandi-text-secondary)">
        抱歉，您访问的页面不存在或已被移除。<br>请检查地址是否正确，或返回首页继续浏览。
      </p>

      <!-- 返回按钮 -->
      <router-link to="/smartrobot"
        class="morandi-btn morandi-btn-primary inline-flex items-center gap-2 rounded-lg px-7 py-3 text-sm font-medium shadow-md transition-shadow hover:shadow-lg">
        <ArrowLeft class="w-4 h-4" />
        <span>返回首页</span>
      </router-link>

      <!-- 底部装饰 -->
      <div class="mt-12 flex items-center justify-center gap-2">
        <div class="w-1.5 h-1.5 rounded-full" style="background: var(--morandi-border)"></div>
        <div class="w-1 h-1 rounded-full" style="background: var(--morandi-divider)"></div>
        <div class="w-1.5 h-1.5 rounded-full" style="background: var(--morandi-border)"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft } from 'lucide-vue-next'
</script>
