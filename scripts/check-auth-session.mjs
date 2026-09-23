/**
 * 前端会话层（静默续期 / 401 重放 / 跨标签页补偿）的可执行验证。
 *
 * 前端没有测试框架（见手册 4.8），此脚本用 Node 自带的 TS 类型剥离直接 import
 * frontend/src/api/auth.ts，以桩替 localStorage / window / fetch，锁定状态机行为。
 * 运行：node scripts/check-auth-session.mjs
 */

const AUTH = new URL('../frontend/src/api/auth.ts', import.meta.url).href

/** 造一个只用于本地判定的 JWT（签名无意义，只关心 payload.exp；jti 用于区分不同令牌） */
function jwt(expSeconds, jti = 'jti_seed') {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
  return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: 'u_1', username: 'u1', exp: expSeconds, jti })}.sig`
}

function makeStorage(initial = {}) {
  const map = new Map(Object.entries(initial))
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
    _dump: () => Object.fromEntries(map),
  }
}

const ok = { code: 200, message: 'ok', data: { hello: 'world' } }

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

/**
 * @param {(url: string, init: any, calls: any[]) => any} handler 返回 {status, body}
 */
async function scenario(storage, handler) {
  const calls = []
  globalThis.localStorage = storage
  globalThis.window = {
    location: { pathname: '/smartrobot', assign: (url) => calls.push({ redirect: url }) },
  }
  globalThis.fetch = async (url, init = {}) => {
    const record = {
      url,
      method: init.method || 'GET',
      headers: Object.fromEntries(new Headers(init.headers ?? {}).entries()),
    }
    calls.push(record)
    const res = handler(url, init, calls) ?? { status: 200, body: ok }
    record.status = res.status
    return {
      ok: res.status >= 200 && res.status < 300,
      status: res.status,
      json: async () => res.body,
    }
  }
  return calls
}

const newSession = (expSec) => ({
  code: 200,
  message: 'ok',
  data: { token: jwt(expSec, 'jti_rotated'), refreshToken: 'RT_NEW', userId: 'u_1', username: 'u1', role: 'editor' },
})
const renewFailed = { code: 400, message: '刷新令牌无效或已过期，请重新登录', data: null }

const auth = await import(AUTH)
const nowSec = () => Math.floor(Date.now() / 1000)

// ---------------------------------------------------------------- 令牌新鲜度判定
console.log('\n[1] 令牌新鲜度与过期判定')
{
  const fresh = jwt(nowSec() + 3600)
  const soon = jwt(nowSec() + 30)
  const expired = jwt(nowSec() - 10)
  check('剩余 1h 判为新鲜', auth.isTokenFresh(fresh))
  check('剩余 30s 判为不新鲜（触发提前续期）', !auth.isTokenFresh(soon))
  check('已过期判为不新鲜', !auth.isTokenFresh(expired))
  check('解不出 exp 的令牌不判过期（避免误踢人）', auth.isTokenFresh('not.a.jwt') && !auth.isTokenExpired('not.a.jwt'))
  check('空令牌判为已过期', auth.isTokenExpired(null))
}

// ---------------------------------------------------------------- 正常放行
console.log('\n[2] 令牌新鲜时直接携带，不打续期接口')
{
  const calls = await scenario(
    makeStorage({ token: jwt(nowSec() + 3600), refreshToken: 'RT_OLD' }),
    () => ({ status: 200, body: ok })
  )
  const data = await auth.request('/api/assistants')
  check('返回统一响应体的 data', data?.hello === 'world')
  check('只发了一次业务请求', calls.filter((c) => c.url === '/api/assistants').length === 1)
  check('未调用续期接口', !calls.some((c) => c.url === '/api/auth/refresh'))
  check('携带 Authorization 头', /^Bearer /.test(calls[0].headers.authorization ?? ''))
}

// ---------------------------------------------------------------- 401 → 续期 → 重放
console.log('\n[3] 本地判为新鲜但服务端 401（登出/改密后令牌已入黑名单）时静默续期并重放一次')
{
  const calls = await scenario(
    makeStorage({ token: jwt(nowSec() + 3600), refreshToken: 'RT_OLD' }),
    (url) => {
      if (url === '/api/auth/refresh') return { status: 200, body: newSession(nowSec() + 3600) }
      if (url === '/api/assistants') {
        return calls.some((c) => c.url === '/api/auth/refresh')
          ? { status: 200, body: ok }
          : { status: 401, body: { code: 401, message: 'Unauthorized', data: null } }
      }
    }
  )
  const data = await auth.request('/api/assistants')
  const refreshCalls = calls.filter((c) => c.url === '/api/auth/refresh')
  const bizCalls = calls.filter((c) => c.url === '/api/assistants')
  check('业务请求被重放一次（共 2 次）', bizCalls.length === 2, `实际 ${bizCalls.length}`)
  check('最终拿到数据而非报错', data?.hello === 'world')
  check('续期接口只调一次', refreshCalls.length === 1)
  check('重放用新令牌，不是旧令牌', bizCalls[1].headers.authorization !== bizCalls[0].headers.authorization)
  check('新令牌已回写本地', auth.getToken() === bizCalls[1].headers.authorization.slice(7))
  check('role 随续期更新（权限判定不滞后）', globalThis.localStorage.getItem('role') === 'editor')
  check('未被踢回登录页', !calls.some((c) => c.redirect))
}

// ---------------------------------------------------------------- 并发单飞
console.log('\n[4] 并发 401 只允许一次续期（轮换令牌并发换发会互相作废）')
{
  let refreshed = false
  const calls = await scenario(
    makeStorage({ token: jwt(nowSec() + 3600), refreshToken: 'RT_OLD' }),
    (url) => {
      if (url === '/api/auth/refresh') {
        refreshed = true
        return { status: 200, body: newSession(nowSec() + 3600) }
      }
      return refreshed ? { status: 200, body: ok } : { status: 401, body: { code: 401, message: 'Unauthorized' } }
    }
  )
  const results = await Promise.all([1, 2, 3, 4, 5].map((i) => auth.request(`/api/r${i}`)))
  check('5 个并发请求全部成功', results.every((r) => r?.hello === 'world'))
  const refreshCount = calls.filter((c) => c.url === '/api/auth/refresh').length
  check('续期接口只被调用 1 次', refreshCount === 1, `实际 ${refreshCount}`)
  check('业务请求共 10 次（5 次 401 + 5 次重放）', calls.filter((c) => c.url.startsWith('/api/r')).length === 10)
}

// ---------------------------------------------------------------- 跨标签页补偿
console.log('\n[5] 两个标签页同时醒着续期：旧令牌已作废时沿用另一页的新会话，而不是登出')
{
  const storage = makeStorage({ token: jwt(nowSec() - 5), refreshToken: 'RT_OLD' })
  let attempts = 0
  const calls = await scenario(storage, (url) => {
    if (url === '/api/auth/refresh') {
      attempts++
      // 第一次：本标签页拿着已被拉黑的旧令牌 → 失败；期间另一标签页写入了新令牌
      if (attempts === 1) {
        storage.setItem('refreshToken', 'RT_OTHER_TAB')
        storage.setItem('token', jwt(nowSec() + 3600))
        return { status: 200, body: renewFailed }
      }
      return { status: 200, body: ok }
    }
    return { status: 200, body: ok }
  })
  const data = await auth.request('/api/assistants')
  check('请求仍成功', data?.hello === 'world')
  check('未跳登录页', !calls.some((c) => c.redirect))
  check('续期只按失败处理一次即沿用新会话', calls.filter((c) => c.url === '/api/auth/refresh').length === 1)
}

// ---------------------------------------------------------------- 会话彻底失效
console.log('\n[6] 无法续期时清态并跳登录页（带 reason=expired）')
{
  const storage = makeStorage({ token: jwt(nowSec() - 5), refreshToken: 'RT_OLD', userId: 'u_1', role: 'admin' })
  const calls = await scenario(storage, (url) =>
    url === '/api/auth/refresh'
      ? { status: 200, body: renewFailed }
      : { status: 401, body: { code: 401, message: 'Unauthorized', data: null } }
  )
  let threw = null
  try {
    await auth.request('/api/assistants')
  } catch (e) {
    threw = e
  }
  check('调用方收到错误（不静默返回脏数据）', threw instanceof Error)
  check('本地会话被清空', Object.keys(globalThis.localStorage._dump()).length === 0)
  check('跳转到 /login?reason=expired', calls.some((c) => c.redirect === '/login?reason=expired'))
}

// ---------------------------------------------------------------- 主动续期
console.log('\n[7] 临期令牌在发出请求前先行换发')
{
  const calls = await scenario(
    makeStorage({ token: jwt(nowSec() + 30), refreshToken: 'RT_OLD' }),
    (url) => (url === '/api/auth/refresh' ? { status: 200, body: newSession(nowSec() + 3600) } : { status: 200, body: ok })
  )
  await auth.request('/api/assistants')
  const order = calls.map((c) => c.url)
  check('续期发生在业务请求之前', order[0] === '/api/auth/refresh' && order[1] === '/api/assistants', order.join(' → '))
  check('业务请求只发一次（无 401 重放）', calls.filter((c) => c.url === '/api/assistants').length === 1)
}

// ---------------------------------------------------------------- WebSocket 建链取令牌
console.log('\n[8] ensureFreshToken 供 WS 建链前复用同一套逻辑')
{
  await scenario(
    makeStorage({ token: jwt(nowSec() - 5), refreshToken: 'RT_OLD' }),
    (url) => (url === '/api/auth/refresh' ? { status: 200, body: newSession(nowSec() + 3600) } : { status: 200, body: ok })
  )
  const t0 = auth.getToken()
  const token = await auth.ensureFreshToken()
  check('返回的是换发后的新令牌', token && token !== t0)
  const anonymous = await scenario(makeStorage({}), () => ({ status: 200, body: ok }))
  check('无令牌时返回 null 且不发请求', (await auth.ensureFreshToken()) === null && anonymous.length === 0)
}

// ---------------------------------------------------------------- multipart 头
console.log('\n[9] FormData 请求体不被塞进 JSON Content-Type（否则后端解析不到文件）')
{
  const calls = await scenario(makeStorage({ token: jwt(nowSec() + 3600) }), () => ({ status: 200, body: ok }))
  await auth.authFetch('/api/call-records/x/recording', { method: 'POST', body: new FormData() })
  const post = calls.find((c) => c.method === 'POST')
  check('未显式设置 application/json', post && !/^application\/json/.test(post.headers['content-type'] ?? ''),
    post?.headers['content-type'])
  check('仍带 Authorization 头', /^Bearer /.test(post?.headers.authorization ?? ''))
  await auth.authFetch('/api/x', { method: 'PUT', body: JSON.stringify({ a: 1 }) })
  const put = calls.find((c) => c.method === 'PUT')
  check('JSON 请求体自动补 Content-Type', /^application\/json/.test(put?.headers['content-type'] ?? ''))
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
