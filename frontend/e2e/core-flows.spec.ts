import { test, expect, type Page } from '@playwright/test'
import path from 'node:path'
import { readFileSync } from 'node:fs'

/**
 * 浏览器级核心链路守卫（v2.75 · 候选 ⑧ 收口）。
 * 会话与"专属助手"夹具由 auth.setup.ts 建立并经 storageState 复用，本文件收尾按精确 id 清理；
 * E2E_LIVE=1 放行两条需要打桩后端的真实模型流用例，缺放行具名 SKIP。
 */

const USER = process.env.E2E_USER || ''
const PASS = process.env.E2E_PASS || ''

/** 惰性读取：夹具文件由 setup 项目在本轮运行里先写出来，模块收集期它还不存在 */
function fixture(): { id: string; name: string; token: string; sessionId: string } {
  return JSON.parse(
    readFileSync(path.join(process.cwd(), 'test-results', '.auth', 'fixture.json'), 'utf8'),
  )
}

test.afterAll(async ({ request }) => {
  // 夹具助手按精确 id 清理（logical delete）；没有夹具文件（全 SKIP 的运行）就不动
  try {
    const f = fixture()
    await request.delete(`/api/assistants/${f.id}`, { headers: { Authorization: `Bearer ${f.token}` } })
  } catch { /* 夹具文件缺失或已清：无可清理 */ }
})

test.describe('错误口令（无会话状态）', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('失败可见且不建立会话', async ({ page }) => {
    test.skip(!USER || !PASS, '缺 E2E_USER/E2E_PASS：登录链路无人核对（具名 SKIP，不算绿）')
    await page.goto('/login')
    await page.getByPlaceholder('请输入用户名').fill(USER)
    await page.getByPlaceholder('请输入密码').fill('definitely-wrong-password')
    await page.getByRole('button', { name: '登录' }).click()
    // 失败必须说出来：错误提示出现，且仍停留在登录页
    await expect(page.locator('body')).toContainText(/用户名或密码|失败|错误/, { timeout: 10_000 })
    await expect(page).toHaveURL(/\/login/)
    await expect(page.evaluate(() => localStorage.getItem('token'))).resolves.toBeNull()
  })
})

test('会话落位：storageState 带着有效令牌直达工作台', async ({ page }) => {
  test.skip(!USER || !PASS, '缺 E2E_USER/E2E_PASS：登录链路无人核对（具名 SKIP，不算绿）')
  await page.goto('/smartrobot')
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15_000 })
  await expect(page.evaluate(() => !!localStorage.getItem('token'))).resolves.toBe(true)
})

test('工作台布局壳：左列表 + 导航选中态 + 登出在位', async ({ page }) => {
  test.skip(!USER || !PASS, '缺 E2E_USER/E2E_PASS：布局壳无人核对（具名 SKIP，不算绿）')
  await page.goto('/smartrobot')
  await expect(page.locator('nav button').first()).toBeVisible({ timeout: 20_000 })
  // PageShell 的导航在位；导航当前项带 aria-current（v2.63 的判据第一次有浏览器级正证）
  const current = page.locator('[aria-current="page"]')
  await expect(current).toHaveCount(1)
  await expect(current).toContainText('对话工作台')
  await expect(page.getByRole('button', { name: /退出|登出/ })).toBeVisible()
})

/**
 * ChatRobot 的输入框在初始化完成前就可交互，而 sendMessage 在 ws 未建时早退（v2.75 · C-144 同族）——
 * 等会话列表渲染出夹具会话（initSessions 完成，connectWebSocket 紧随其后）再发；
 * 建链的异步窗口由 C-144 的待发队列兜底。欢迎气泡不能当信号：有历史时它不渲染。
 */
async function openChatSession(page: Page): Promise<void> {
  await page.goto(`/chatrobot?assistantId=${fixture().id}&sessionId=${fixture().sessionId}`)
  await expect(page.locator('body')).toContainText('e2e-fixture', { timeout: 30_000 })
}

test('工具回合：工具卡与回答实时可见，历史留痕', async ({ page }) => {
  test.skip(process.env.E2E_LIVE !== '1', '需打桩后端 + E2E_LIVE=1 显式放行（见 DEVELOPMENT §9），缺放行具名 SKIP')
  test.skip(!USER || !PASS, '缺 E2E_USER/E2E_PASS')
  await openChatSession(page)
  const input = page.getByPlaceholder('请输入您想问的问题')
  await input.fill('北京天气怎么样')
  await input.press('Enter')
  // 工具调用卡片（get_weather）与最终回答都必须真实到达浏览器（S-24 收口的产品级正证）
  await expect(page.locator('body')).toContainText('get_weather', { timeout: 20_000 })
  await expect(page.locator('body')).toContainText('查到了', { timeout: 20_000 })
  // 刷新后历史里仍有工具轨迹（S-19 的产品级正证）：renderSessionHistory 从会话历史取数渲染
  await page.reload()
  await openChatSession(page)
  await expect(page.locator('body')).toContainText('get_weather', { timeout: 15_000 })
})

test('失败回合：错误可见，历史带失败标记', async ({ page }) => {
  test.skip(process.env.E2E_LIVE !== '1', '需打桩后端 + E2E_LIVE=1 显式放行（见 DEVELOPMENT §9），缺放行具名 SKIP')
  test.skip(!USER || !PASS, '缺 E2E_USER/E2E_PASS')
  await openChatSession(page)
  const input = page.getByPlaceholder('请输入您想问的问题')
  await input.fill('这条消息必失败')
  await input.press('Enter')
  // 失败必须说出来：断言必须咬**具体的错误文案**——用 /失败/ 会被用户气泡里的"必失败"假阳性满足，
  // 断言瞬间通过后立刻 reload 会把在途回合取消掉（取消路径落库没有 failReason，这是正确行为不是缺陷）
  await expect(page.locator('body')).toContainText('暂时不可用', { timeout: 20_000 })
  // 刷新后历史带失败标记（S-22 的产品级正证）：错误帧到达时错误回调已落库 [user + fail_reason 行]
  await page.reload()
  await openChatSession(page)
  await expect(page.locator('body')).toContainText('模型服务异常', { timeout: 15_000 })
})
