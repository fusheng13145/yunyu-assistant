import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'

// 主题配置常量
const THEME_KEY = 'yunyu-theme-mode'
type ThemeMode = 'light' | 'dark' | 'system'

/** 应用主题样式 */
function applyTheme(mode: ThemeMode) {
  const html = document.documentElement
  const isSystemDark = window.matchMedia('(prefers-color-scheme: dark)').matches

  html.classList.toggle('dark', mode === 'dark' || (mode === 'system' && isSystemDark))
}

// 初始化主题（防止页面闪烁，挂载前同步执行）
const savedTheme = (localStorage.getItem(THEME_KEY) || 'system') as ThemeMode
applyTheme(savedTheme)

// 开启主题切换过渡（修复：直接获取 document.documentElement）
document.documentElement.classList.add('theme-transition')

// 监听系统主题变化（仅跟随系统模式时生效）
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
  if (localStorage.getItem(THEME_KEY) === 'system') {
    document.documentElement.classList.toggle('dark', e.matches)
  }
})

// 初始化应用
const app = createApp(App)
app.use(router).mount('#app')