import { defineConfig } from '@playwright/test'
import path from 'node:path'

/**
 * 浏览器级 E2E 守卫（v2.75 · 候选 ⑧ 收口）。
 *
 * 与真机冒烟（scripts/smoke.sh）同一运行契约：需要外部后端 + 数据库，**刻意不进 CI**——
 * CI 既不装运行时也不连库（手册 4.8）。本地跑法见 docs/DEVELOPMENT.md §9：
 *   1) 后端就绪（默认 http://127.0.0.1:8091；E2E 走 vite 代理，VITE_PROXY_TARGET 同步指向后端）
 *   2) vite dev 自行后台拉起（`VITE_PROXY_TARGET=http://127.0.0.1:8091 npm run dev`）
 *   3) E2E_USER / E2E_PASS 提供登录账号（.scratch/smoke-admin.env 的种子管理员可复用）
 * 带真实模型流的两条用例（工具回合 / 失败回合）需要后端以打桩环境启动（127.0.0.1:8123 桩），
 * 并以 E2E_LIVE=1 显式放行——缺放行时整条具名 SKIP，绝不静默假绿。
 *
 * 登录在限流 AUTH 档（容量 5/窗口）：setup 项目登录一次落 storageState，core 项目全程复用——
 * 逐用例各自登录会打光额度，让判据"时绿时不绿"。
 * E2E_START_WEB=1 可让 playwright 自己拉起 vite（仅作应急）：Windows 下该方式拉起的 dev server
 * 在跑过一轮带 WS 代理的用例后会失稳（连接拒绝/列表加载失败这类伪缺陷），首选上面第 2 步的手动拉起。
 */
const backend = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8091'
// ESM 下没有 __dirname：playwright 恒从 frontend/ 起跑，cwd 即包根
const stateFile = path.join(process.cwd(), 'test-results', '.auth', 'state.json')

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
  },
  webServer: process.env.E2E_START_WEB === '1'
    ? {
        command: 'npm run dev',
        url: process.env.E2E_BASE_URL || 'http://127.0.0.1:5173',
        reuseExistingServer: true,
        // vite 中途死掉的后果是"列表加载失败/连接拒绝"这类伪缺陷，把它的输出带出来才可诊断
        stdout: 'pipe',
        stderr: 'pipe',
        env: { VITE_PROXY_TARGET: backend },
      }
    : undefined,
  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
    },
    {
      name: 'core',
      testIgnore: [/auth\.setup\.ts/, /recording-mix\.spec\.ts/],
      dependencies: ['setup'],
      use: { storageState: stateFile },
    },
    {
      // 录音混音取证（v2.87 · C-165）：不登录、不连后端，只需 vite dev。
      // headless 下没有真实音频输出设备也要让 AudioContext 跑起来，否则判据会读到自己造出来的静音
      name: 'media',
      testMatch: /recording-mix\.spec\.ts/,
      use: {
        launchOptions: {
          args: ['--autoplay-policy=no-user-gesture-required'],
          ignoreDefaultArgs: ['--mute-audio'],
        },
      },
    },
  ],
})
