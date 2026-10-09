import { test as setup, expect } from '@playwright/test'
import path from 'node:path'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'

/**
 * 会话与夹具（v2.75）：整个运行只在这里登录一次，storageState 供后续用例复用；
 * 顺路自建一个专属助手作为对话夹具——列表接口严格按属主过滤（pageByUser），
 * 种子数据不属于登录账号是常态，依赖它会"时绿时不绿"。
 * 登录接口在限流 AUTH 档（容量 5/窗口）：逐用例各自登录会把额度打光，共享会话是判据稳定的前提。
 * 收尾清理本运行创建的助手（按精确 id），上次运行的残渣在本轮 setup 先扫掉。
 */
const dir = path.join(process.cwd(), 'test-results', '.auth')
const stateFile = path.join(dir, 'state.json')
const fixtureFile = path.join(dir, 'fixture.json')
const NAME = 'E2E探针助手'

setup('authenticate', async ({ page, request }) => {
  const user = process.env.E2E_USER || ''
  const pass = process.env.E2E_PASS || ''
  setup.skip(!user || !pass, '缺 E2E_USER/E2E_PASS：会话夹具不建立，后续用例全部具名 SKIP（不算绿）')
  await page.goto('/login')
  await page.getByPlaceholder('用户名 / 邮箱 / 手机号').fill(user)
  await page.getByPlaceholder('请输入密码').fill(pass)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15_000 })
  await expect(page.evaluate(() => !!localStorage.getItem('token'))).resolves.toBe(true)
  await page.context().storageState({ path: stateFile })
  const token = await page.evaluate(() => localStorage.getItem('token') || '')
  const auth = { Authorization: `Bearer ${token}` }

  // 上次运行残渣：同名助手按精确 id 清掉（logical delete，不留孤儿夹具）
  const stale = await request.get(`/api/assistants/page?page=1&pageSize=50&keyword=${encodeURIComponent(NAME)}`, { headers: auth })
  const staleList = (await stale.json())?.data?.list ?? []
  for (const a of staleList) {
    if (a.name === NAME) await request.delete(`/api/assistants/${a.id}`, { headers: auth })
  }

  const created = await request.post('/api/assistants', {
    headers: auth,
    data: { name: NAME, personality: 'e2e fixture', modelName: 'deepseek-chat', temperature: 0.7, maxTokens: 512, voice: 'Cherry' },
  })
  const body = await created.json()
  expect(body?.data?.id, '创建 E2E 专属助手失败：' + JSON.stringify(body)).toBeTruthy()

  // 会话夹具：历史回看（renderSessionHistory）按 session 维度取数，工具/失败轨迹的刷新断言走它
  const session = await request.post('/api/sessions', {
    headers: auth,
    data: { assistantId: body.data.id, title: 'e2e-fixture' },
  })
  const sessionBody = await session.json()
  expect(sessionBody?.data?.id, '创建 E2E 会话失败：' + JSON.stringify(sessionBody)).toBeTruthy()

  mkdirSync(dir, { recursive: true })
  writeFileSync(fixtureFile, JSON.stringify({ id: body.data.id, name: NAME, token, sessionId: sessionBody.data.id }))
})
