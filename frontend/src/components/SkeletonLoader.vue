<template>
  <div class="w-72 flex-shrink-0 p-2 flex flex-col max-h-full">
    <div class="mb-4 flex flex-col items-center text-center p-3">
      <div class="h-8 w-24 rounded-md animate-pulse mb-1" style="background: var(--geek-input-bg)"></div>
      <div class="h-4 w-32 rounded-sm animate-pulse" style="background: var(--geek-input-bg)"></div>
    </div>
    <div class="h-12 w-full rounded-full animate-pulse mb-4" style="background: var(--geek-input-bg)"></div>
    <div class="flex-1 min-h-0 space-y-3 px-1 pt-1 pb-4">
      <div v-for="n in 4" :key="n"
        class="p-4 rounded-lg animate-pulse"
        style="background: var(--geek-surface); border: 1px solid var(--geek-border);"
        :style="{ animationDelay: `${n * 0.15}s` }">
        <div class="flex items-center mb-3">
          <div class="w-10 h-10 rounded-full animate-pulse mr-3" style="background: var(--geek-input-bg)"></div>
          <div class="flex-1">
            <div class="h-5 w-28 rounded-md animate-pulse mb-1.5" style="background: var(--geek-input-bg)"></div>
            <div class="h-3.5 w-16 rounded-sm animate-pulse" style="background: var(--geek-border)"></div>
          </div>
        </div>
        <div class="space-y-2 mb-3">
          <div class="h-3.5 w-full rounded-sm animate-pulse" style="background: var(--geek-border)"></div>
          <div class="h-3.5 w-4/5 rounded-sm animate-pulse" style="background: var(--geek-border)"></div>
        </div>
        <div class="flex items-center justify-between pt-2" style="border-top: 1px solid var(--geek-divider)">
          <div class="flex items-center gap-2">
            <div class="w-2 h-2 rounded-full animate-pulse" style="background: var(--geek-text-muted)"></div>
            <div class="h-3.5 w-14 rounded-sm animate-pulse" style="background: var(--geek-border)"></div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div class="flex-1 flex flex-col min-w-0">
    <div class="h-full rounded-xl flex flex-col" style="background: var(--geek-surface); border: 1px solid var(--geek-border);">
      <div class="flex items-center justify-between py-4 px-6" style="border-bottom: 1px solid var(--geek-divider)">
        <div class="h-6 w-36 rounded-md animate-pulse" style="background: var(--geek-input-bg)"></div>
        <div class="flex items-center gap-2">
          <div class="h-8 w-16 rounded-md animate-pulse" style="background: var(--geek-input-bg)"></div>
          <div class="h-8 w-16 rounded-md animate-pulse" style="background: var(--geek-input-bg)"></div>
          <div class="h-8 w-16 rounded-md animate-pulse" style="background: var(--geek-input-bg)"></div>
        </div>
      </div>
      <div class="flex-1 min-h-0 flex items-center justify-center">
        <div class="text-center space-y-4">
          <div class="w-24 h-24 rounded-full mx-auto animate-pulse" style="background: var(--geek-input-bg)"></div>
          <div class="h-6 w-40 rounded-md mx-auto animate-pulse" style="background: var(--geek-input-bg)"></div>
          <div class="h-4 w-56 rounded-sm mx-auto animate-pulse" style="background: var(--geek-border)"></div>
        </div>
      </div>
      <div style="border-top: 1px solid var(--geek-divider)" class="p-4">
        <div class="flex items-center gap-3">
          <div class="flex-1 h-12 rounded-lg animate-pulse" style="background: var(--geek-input-bg)"></div>
          <div class="w-12 h-12 rounded-lg animate-pulse" style="background: var(--geek-input-bg)"></div>
          <div class="w-20 h-12 rounded-lg animate-pulse" style="background: var(--geek-input-bg)"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
</script>
