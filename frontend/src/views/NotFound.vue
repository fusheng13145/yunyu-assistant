<template>
  <div class="morandi-body bg-morandi-gradient h-screen w-full flex items-center justify-center relative overflow-hidden antialiased">
    <div class="text-center px-6 animate-fade-up">
      <!-- 顶部装饰线 -->
      <div class="divider-line mx-auto mb-8"></div>

      <!-- 404 主数字 -->
      <h1 class="error-code serif font-bold mb-4 select-none">404</h1>

      <!-- 标题文案 -->
      <p class="error-title serif text-2xl font-medium mb-3 tracking-wide">页面未找到</p>

      <!-- 描述说明 -->
      <p class="error-desc text-sm max-w-sm mx-auto leading-relaxed mb-10">
        抱歉，您访问的页面不存在或已被移除。<br>请检查地址是否正确，或返回首页继续浏览。
      </p>

      <!-- 跳转按钮 -->
      <router-link
        to="/smartrobot"
        class="morandi-btn morandi-btn-primary inline-flex items-center gap-2 rounded-lg px-7 py-3 text-sm font-medium shadow-md transition-shadow hover:shadow-lg"
      >
        <ArrowLeft class="w-4 h-4" />
        <span>返回首页</span>
      </router-link>

      <!-- 底部装饰圆点 -->
      <div class="decor-dot-group mt-12 flex items-center justify-center gap-2">
        <span class="dot dot-lg"></span>
        <span class="dot dot-sm"></span>
        <span class="dot dot-lg"></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft } from 'lucide-vue-next'
</script>

<style scoped>
/* 顶部装饰线 */
.divider-line {
  width: 16px;
  height: 1px;
  background: var(--morandi-border-strong);
}

/* 404 数字样式 */
.error-code {
  font-size: 136px;
  line-height: 1;
  color: var(--morandi-text-muted);
  letter-spacing: -0.04em;
}

/* 标题文本 */
.error-title {
  color: var(--morandi-text);
}

/* 描述文本 */
.error-desc {
  color: var(--morandi-text-secondary);
}

/* 装饰圆点组 */
.decor-dot-group .dot {
  border-radius: 50%;
}
.dot-lg {
  width: 6px;
  height: 6px;
  background: var(--morandi-border);
}
.dot-sm {
  width: 4px;
  height: 4px;
  background: var(--morandi-divider);
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