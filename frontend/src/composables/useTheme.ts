import { ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'yunyu-theme-mode'
const HTML_CLASS_DARK = 'dark'

const themeMode = ref<ThemeMode>((localStorage.getItem(STORAGE_KEY) as ThemeMode) || 'system')

function getSystemPrefersDark(): boolean {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

function applyTheme(mode: ThemeMode): void {
  const html = document.documentElement

  if (mode === 'dark') {
    html.classList.add(HTML_CLASS_DARK)
  } else if (mode === 'light') {
    html.classList.remove(HTML_CLASS_DARK)
  } else {
    // system mode
    if (getSystemPrefersDark()) {
      html.classList.add(HTML_CLASS_DARK)
    } else {
      html.classList.remove(HTML_CLASS_DARK)
    }
  }
}

// 模块加载时立即应用主题并监听系统主题变化（无需组件实例）
applyTheme(themeMode.value)

const mediaQuery = window.matchMedia?.('(prefers-color-scheme: dark)')
if (mediaQuery) {
  mediaQuery.addEventListener('change', () => {
    if (themeMode.value === 'system') {
      applyTheme('system')
    }
  })
}

watch(themeMode, (newMode) => {
  localStorage.setItem(STORAGE_KEY, newMode)
  applyTheme(newMode)
})

export function useTheme() {
  const setTheme = (mode: ThemeMode) => {
    themeMode.value = mode
  }

  const isDark = ref(getSystemPrefersDark())

  const updateIsDark = () => {
    if (themeMode.value === 'dark') {
      isDark.value = true
    } else if (themeMode.value === 'light') {
      isDark.value = false
    } else {
      isDark.value = getSystemPrefersDark()
    }
  }

  watch(themeMode, updateIsDark, { immediate: true })

  return {
    themeMode,
    setTheme,
    isDark,
  }
}
