<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div id="app" class="morandi-body theme-transition morandi-scroll">
    <RouterView v-slot="{ Component }">
      <component :is="Component" :key="route.path" />
    </RouterView>
  </div>
</template>

<style scoped>
/* 这里可以写页面级专属样式，保持和全局莫兰迪风格统一 */
</style>