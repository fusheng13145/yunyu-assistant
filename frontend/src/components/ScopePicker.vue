<template>
  <fieldset class="rounded px-3 py-2.5" style="border: 1px solid var(--geek-border)">
    <legend class="text-xs px-1" style="color: var(--geek-text-secondary)">{{ legend }}</legend>
    <label
      v-for="option in options"
      :key="option.value"
      class="flex items-start gap-2 py-1 cursor-pointer"
    >
      <input v-model="selected" type="checkbox" :value="option.value" class="mt-0.5" />
      <span class="text-xs leading-relaxed">
        <span class="mono" style="color: var(--geek-text)">{{ option.value }}</span>
        <span style="color: var(--geek-text-muted)">— {{ option.label }}</span>
      </span>
    </label>
    <p v-if="hint" class="text-xs mt-1.5" style="color: var(--geek-text-faint)">{{ hint }}</p>
  </fieldset>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: string[]
  options: { value: string; label: string }[]
  legend?: string
  hint?: string
}>()

const emits = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
}>()

// 勾选框唯一持有的是 string[]，拼逗号串留给调用方——服务端契约是整串替换
const selected = computed({
  get: () => props.modelValue,
  set: (value) => emits('update:modelValue', value),
})
</script>
