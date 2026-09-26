<script setup lang="ts">
import { useRoute } from 'vue-router'
import { useNotification } from './composables/useNotification'

const route = useRoute()
const { notification } = useNotification()
</script>

<template>
  <div id="app" class="geek-body theme-transition geek-scroll">
    <!-- 全局通知：唯一挂载点，各视图通过 useNotification().show() 触发 -->
    <transition name="notification">
      <div
        v-if="notification.show"
        class="notification-toast geek-notification"
        :class="[`geek-notification--${notification.type}`]"
        role="status"
      >
        {{ notification.message }}
      </div>
    </transition>

    <RouterView v-slot="{ Component }">
      <component :is="Component" :key="route.path" />
    </RouterView>
  </div>
</template>

<style scoped>
.notification-toast {
  position: fixed;
  top: 1rem;
  right: 1rem;
  z-index: 100;
  padding: 0.75rem 1.5rem;
}

.notification-enter-active,
.notification-leave-active {
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.notification-enter-from,
.notification-leave-to {
  opacity: 0;
  transform: translateX(24px);
}
</style>
