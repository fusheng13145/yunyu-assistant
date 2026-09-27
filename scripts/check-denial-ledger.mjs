/**
 * 开放平台拒绝台账读数分格验证（v2.50 · C-111 的门禁侧）。
 *
 * 锁定的事实：`GET /api/openapi/denials` 返回的行里 `app: null` 是"认不出归属"的全局格
 * （无 Key / 错 Key / 握手超限），它一旦被 filter 写成 `row.app === appId` 而 appId 又可能为空，
 * 全局计数就会被伪装成某个应用的被拒次数；而"这次读数请求失败了"如果被渲染成"0 次被拒"，
 * 就是 v2.49 收口通话录音上传时那条同一类缺陷（故障被报成好消息）在管理页复发。
 * 口径与 check-recording-upload.mjs 一致：无测试框架，用 Node 的 TS 类型剥离直接 import 生产模块。
 * 运行：node scripts/check-denial-ledger.mjs
 */

const MOD = new URL('../frontend/src/utils/denialLedger.ts', import.meta.url).href
const VIEW = new URL('../frontend/src/views/Apps.vue', import.meta.url)
const API = new URL('../frontend/src/api/openapi.ts', import.meta.url)

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

const { ledgerCellFor, unattributedRows } = await import(MOD)
const { readFileSync } = await import('node:fs')

/** 与后端 Entry 形状一致的行 */
function row(app, kind = 'SCOPE_DENIED', count = 1) {
  return { app, kind, kindLabel: '缺少所需能力', count }
}

console.log('\n[1] 归属：未识别的行不挂到任何应用名下')
{
  const rows = [row('app-1'), row(null, 'KEY_INVALID', 42), row('app-2')]
  const cell = ledgerCellFor(rows, 'app-1', false)
  check('应用格只含自己的行', cell.rows.length === 1 && cell.rows[0].app === 'app-1')
  check('有行 ⇒ tone=denied（前端按它渲染具体种类）', cell.tone === 'denied')
  check('全局格不会被算进任何应用（42 次匿名试 Key 不是"你的 Key 被拒 42 次"）',
    !cell.rows.some(r => r.app === null))
  check('另一个应用只拿到自己的那一行', ledgerCellFor(rows, 'app-2', false).rows.length === 1)
  check('名下无行的应用是"确认零次"，不是"读不到"', ledgerCellFor(rows, 'app-3', false).tone === 'quiet')
}

console.log('\n[2] 三态：读不到 ≠ 0 次被拒')
{
  check('failed=true 且 rows 为空 ⇒ unavailable（不能显示"0 次"）',
    ledgerCellFor([], 'app-1', true).tone === 'unavailable')
  check('failed=true 且 rows 仍有上一次的数据 ⇒ 也一律 unavailable（不用旧数冒充新数）',
    ledgerCellFor([row('app-1')], 'app-1', true).tone === 'unavailable')
  check('unavailable 时不返回任何行（视图没有可误显示的内容）',
    ledgerCellFor([row('app-1')], 'app-1', true).rows.length === 0)
  check('响应缺 data/非数组 ⇒ unavailable（读数接口形状漂移不静默当成零）',
    ledgerCellFor(undefined, 'app-1', false).tone === 'unavailable'
      && ledgerCellFor(null, 'app-1', false).tone === 'unavailable'
      && ledgerCellFor({}, 'app-1', false).tone === 'unavailable')
  check('appId 为空 ⇒ unavailable（空串配 null 行是错格的唯一入口，直接堵掉）',
    ledgerCellFor([row(null)], '', false).tone === 'unavailable'
      && ledgerCellFor([row(null)], null, false).tone === 'unavailable')
}

console.log('\n[3] 全局未归属格')
{
  const rows = [row('app-1'), row(null), row('', 'KEY_MISSING'), { kind: 'RATE_LIMITED' }]
  const global = unattributedRows(rows)
  check('null 计入未归属', global.some(r => r.app === null))
  check('空串计入未归属（后端只承诺 null，前端按假值兜住更宽的口径）',
    global.some(r => r.app === ''))
  check('缺 app 字段的行计入未归属而不是被丢弃（有行就必须落在某一格）',
    global.some(r => r.app === undefined && r.kind === 'RATE_LIMITED'))
  check('正常应用行不混进全局格', !global.some(r => r.app === 'app-1'))
  check('rows 缺失时返回空数组（不抛）',
    unattributedRows(undefined).length === 0 && unattributedRows(null).length === 0)
}

console.log('\n[4] 视图与接口接入收口（防第三次复制粘贴）')
{
  const view = readFileSync(VIEW, 'utf8')
  const api = readFileSync(API, 'utf8')
  check('Apps.vue 经统一入口 ledgerCellFor 判定三态',
    /from\s*'[^']*utils\/denialLedger'/.test(view) && view.includes('cellFor(app.id).tone'))
  check('Apps.vue 不再自己按 app 字段 filter（口径只能有一处）',
    !/denialRows\.value\.filter\(\s*row\s*=>\s*row\.app\s*===/.test(view))
  check('"读不到"有独立的可见文案（不是复用 0 次的占位）',
    view.includes('台账读不到') && view.includes('0 次'))
  check('未归属格有全局展示位，并写明内存/单实例/窗口口径',
    view.includes('unattributed') && view.includes('重启清零'))
  check('读数走 api/openapi.ts 的 fetchDenials（视图不直连 fetch）',
    /export function fetchDenials/.test(api) && api.includes('/openapi/denials'))
  check('fetchDenials 带 hours 参数（窗口是接口的一部分，前端不能省）',
    /fetchDenials\(hours = 24\)/.test(api))
  check('util 不 import 任何相对模块（Node 类型擦除门禁的前提）',
    !/^import .*from ['"]\.\.?\//m.test(readFileSync(new URL(MOD), 'utf8')))
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
