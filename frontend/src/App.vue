<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div id="app" class="geek-body theme-transition geek-scroll">
    <RouterView v-slot="{ Component }">
      <component :is="Component" :key="route.path" />
    </RouterView>
  </div>
</template>

<style scoped>
/* 这里可以写页面级专属样式，保持和全局纯白极客风格统一 */
</style>