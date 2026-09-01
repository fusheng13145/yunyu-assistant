<template>
  <div class="geek-body geek-grid-bg h-screen w-full flex items-center justify-center relative overflow-hidden antialiased">
    <div class="text-center px-6 animate-fade-up relative z-10">
      <!-- 顶部等宽状态标签 -->
      <p class="status-kicker mono mb-6">ERROR // PAGE_NOT_FOUND</p>

      <!-- 404 主数字 -->
      <h1 class="error-code font-display mb-4 select-none">404</h1>

      <!-- 标题文案 -->
      <p class="error-title text-2xl font-bold mb-3 tracking-tight">页面未找到</p>

      <!-- 描述说明 -->
      <p class="error-desc text-sm max-w-sm mx-auto leading-relaxed mb-10">
        抱歉，您访问的页面不存在或已被移除。<br>请检查地址是否正确，或返回首页继续浏览。
      </p>

      <!-- 跳转按钮 -->
      <router-link
        to="/smartrobot"
        class="geek-btn geek-btn-primary inline-flex items-center gap-2 px-7 py-3 text-sm"
      >
        <ArrowLeft class="w-4 h-4" />
        <span>返回首页</span>
      </router-link>

      <!-- 底部装饰：终端风格方块 -->
      <div class="decor-block mt-12 flex items-center justify-center gap-1.5">
        <span class="block-item block-lg"></span>
        <span class="block-item block-sm"></span>
        <span class="block-item block-lg"></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft } from 'lucide-vue-next'
</script>

<style scoped>
/* 等宽状态标签 */
.status-kicker {
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.16em;
  color: var(--geek-error);
  text-transform: uppercase;
}

/* 404 数字样式 */
.error-code {
  font-size: 128px;
  line-height: 1;
  color: var(--geek-text);
  letter-spacing: -0.05em;
}

/* 标题文本 */
.error-title {
  color: var(--geek-text);
}

/* 描述文本 */
.error-desc {
  color: var(--geek-text-secondary);
}

/* 装饰方块组（方形、硬朗） */
.block-lg {
  width: 6px;
  height: 6px;
  background: var(--geek-border-strong);
}
.block-sm {
  width: 4px;
  height: 4px;
  background: var(--geek-accent);
}

/* 全局淡入动画 */
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
