<template>
  <div class="theme-switcher" role="radiogroup" aria-label="切换主题">
    <button
      v-for="opt in options"
      :key="opt.value"
      :class="['theme-opt', { active: modelValue === opt.value }]"
      :aria-checked="modelValue === opt.value"
      role="radio"
      @click="$emit('update:modelValue', opt.value)"
    >
      <span class="theme-icon" v-html="opt.icon"></span>
      <span class="theme-label">{{ opt.label }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import type { ThemeMode } from '../composables/useTheme'

defineProps<{ modelValue: ThemeMode }>()
defineEmits<{
  (e: 'update:modelValue', value: ThemeMode): void
}>()

const options = [
  {
    value: 'light' as ThemeMode,
    label: '亮色',
    icon: `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>`
  },
  {
    value: 'dark' as ThemeMode,
    label: '暗色',
    icon: `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/></svg>`
  },
  {
    value: 'system' as ThemeMode,
    label: '跟随系统',
    icon: `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>`
  }
]
</script>

<style scoped>
.theme-switcher {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border-radius: var(--radius-md);
  background: var(--morandi-input-bg);
  border: 1px solid var(--morandi-border);
}
.theme-opt {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--morandi-text-muted);
  cursor: pointer;
  border: none;
  background: transparent;
  transition: all 200ms ease;
  white-space: nowrap;
}
.theme-opt:hover:not(.active) {
  color: var(--morandi-text-secondary);
  background: var(--morandi-primary-light);
}
.theme-opt.active {
  background: var(--morandi-surface-raised);
  color: var(--morandi-primary);
  box-shadow: 0 1px 3px var(--morandi-shadow-color);
  font-weight: 500;
}
.theme-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.8;
}
.theme-opt.active .theme-icon {
  opacity: 1;
}
</style>
