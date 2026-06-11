import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'
import router from './router'

// 初始化主题（在挂载前执行，避免闪烁）
const THEME_KEY = 'yunyu-theme-mode'
const savedTheme = localStorage.getItem(THEME_KEY) || 'system'

function applyInitialTheme(mode: string) {
  const html = document.documentElement
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false

  if (mode === 'dark' || (mode === 'system' && prefersDark)) {
    html.classList.add('dark')
  } else {
    html.classList.remove('dark')
  }
}

// 立即应用主题（同步，在渲染前）
applyInitialTheme(savedTheme)

// 为根元素添加平滑过渡类
document.documentElement.classList.add('theme-transition')

// 监听系统主题变化
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
  if (localStorage.getItem(THEME_KEY) === 'system') {
    document.documentElement.classList.toggle('dark', e.matches)
  }
})

const app = createApp(App)
app.use(createPinia())
app.use(router).mount('#app')
