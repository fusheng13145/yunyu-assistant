import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// 本地后端地址：默认仍是 8080（Boot 原行为）。
// 8080 被占用或多实例并行时用 VITE_PROXY_TARGET 覆盖，不改代码。
const backend = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],

  // 路径别名
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@assets': path.resolve(__dirname, './src/assets'),
      '@components': path.resolve(__dirname, './src/components')
    }
  },

  server: {
    // 本地开发端口，可按需修改
    port: 5173,
    open: true, // 启动自动打开浏览器
    host: '0.0.0.0', // 允许局域网访问

    proxy: {
      // 后端 HTTP 接口代理
      '/api': {
        target: backend,
        changeOrigin: true,
        timeout: 30000, // 超时 30s，适配大文件/长接口
      },

      // 普通 WebSocket 代理
      '/ws': {
        target: backend.replace(/^http/, 'ws'),
        ws: true,
        changeOrigin: true
      },

      // 语音通话 WebSocket 代理
      '/ws-voice': {
        target: backend.replace(/^http/, 'ws'),
        ws: true,
        changeOrigin: true
      }
    }
  },

  // 生产构建优化
  build: {
    outDir: 'dist',
    assetsDir: 'static',
    chunkSizeWarningLimit: 1000, // 增大包体积警告阈值
    // 分包优化
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('vue')) {
            return 'vue';
          }
          if (id.includes('axios')) {
            return 'vendor';
          }
        }
      }
    }
  }
})