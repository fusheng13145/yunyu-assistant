/**
 * 会话历史回看是否把"知识库检索状态"一路带到角标（v2.41 · C-91 的前端侧）。
 *
 * 后端 v2.41 起把 {docCount, docName[], failed} 写进 records.knowledgebase_info，
 * 并把该列以对象形式暴露在 GET /api/sessions/{id}/messages 上。此脚本锁定两件事：
 *  1) 历史记录 → 展示消息的映射不丢这个字段（丢了角标只能活到刷新页面为止）；
 *  2) ChatRobot.vue 确实走这份映射，而不是又内联一份（内联正是上一版丢字段的原因）。
 * 口径与 check-auth-session.mjs / check-knowledgebase-flag.mjs 一致：无测试框架，
 * 用 Node 的 TS 类型剥离直接 import 生产模块（import type 会被剥离）。
 * 运行：node scripts/check-history-record.mjs
 */

import { readFileSync } from 'node:fs'

const MAP_MOD = new URL('../frontend/src/utils/mapHistoryRecord.ts', import.meta.url).href
const FLAG_MOD = new URL('../frontend/src/utils/knowledgebaseFlag.ts', import.meta.url).href
const CHAT_ROBOT = new URL('../frontend/src/views/ChatRobot.vue', import.meta.url)
const SESSION_API = new URL('../frontend/src/api/session.ts', import.meta.url)
const ADMIN_VIEW = new URL('../frontend/src/views/Admin.vue', import.meta.url)

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

const { mapHistoryRecord } = await import(MAP_MOD)
const { knowledgebaseFlag } = await import(FLAG_MOD)

console.log('\n[1] 角色映射不回归（抽函数不能顺手改口径）')
{
  check('role=0 → user，text 即 message',
    JSON.stringify(mapHistoryRecord({ id: '1', role: 0, message: '问' }))
      === JSON.stringify({ role: 'user', text: '问' }))
  check('role=1 → assistant，带 costTime',
    JSON.stringify(mapHistoryRecord({ id: '2', role: 1, message: '答', costTime: 1200 }))
      === JSON.stringify({ role: 'assistant', text: '答', costTime: 1200 }))
  // v2.72 · C-141：工具回路在应用侧执行（S-24 收口），轨迹随 records 落库（S-19 收口），
  // role 2/3 分支恢复——形状与实时帧 push-tool-call/push-tool-result 落下的消息一致
  check('role=2 → 工具调用卡（toolArgs 原样 JSON 作 text）',
    JSON.stringify(mapHistoryRecord({ id: '3', role: 2, message: '', toolName: 'weather', toolArgs: '{"a":1}' }))
      === JSON.stringify({ role: 'tool_call', toolName: 'weather', text: '{"a":1}' }))
  check('role=3 → 工具结果卡（JSON 字符串标量解析回原文）',
    JSON.stringify(mapHistoryRecord({ id: '4', role: 3, message: '', toolName: 'weather', toolResult: '"晴"' }))
      === JSON.stringify({ role: 'tool_result', toolName: 'weather', toolResult: '晴', text: '晴' }))
  check('role=3 坏值/非标量原样返回不炸',
    mapHistoryRecord({ id: '4b', role: 3, toolName: 't', toolResult: 'not json' })?.text === 'not json')
  check('未知 role → null（交给上层 filter）',
    mapHistoryRecord({ id: '5', role: 9, message: 'x' }) === null)
}

console.log('\n[2] 检索状态必须穿过历史映射抵达展示消息（v2.41 的全部意义）')
{
  const failed = mapHistoryRecord({
    id: '6', role: 1, message: '答',
    knowledgebase: { docCount: 0, docName: [], failed: true },
  })
  check('故障态字段未被映射吃掉', failed?.knowledgebase?.failed === true,
    `实际 ${JSON.stringify(failed?.knowledgebase)}`)
  check('故障态在历史里仍渲染为 failed 角标', knowledgebaseFlag(failed?.knowledgebase) === 'failed')

  const cited = mapHistoryRecord({
    id: '7', role: 1, message: '答',
    knowledgebase: { docCount: 2, docName: ['A.pdf', 'B.pdf'], failed: false },
  })
  check('命中态带出文档名', JSON.stringify(cited?.knowledgebase?.docName) === JSON.stringify(['A.pdf', 'B.pdf']))
  check('命中态渲染为 cited 角标', knowledgebaseFlag(cited?.knowledgebase) === 'cited')

  const none = mapHistoryRecord({
    id: '8', role: 1, message: '答',
    knowledgebase: { docCount: 0, docName: [], failed: false },
  })
  check('真没查到 → none', knowledgebaseFlag(none?.knowledgebase) === 'none')

  const legacy = mapHistoryRecord({ id: '9', role: 1, message: '答' })
  check('旧数据/未挂知识库整段缺失 → none 而非报错', knowledgebaseFlag(legacy?.knowledgebase) === 'none')
}

console.log('\n[3] 三态两两不同形：故障绝不能被读成"知识库没答案"')
{
  const flag = (kb) => knowledgebaseFlag(mapHistoryRecord({ id: 'x', role: 1, message: '答', knowledgebase: kb })?.knowledgebase)
  const shapes = { failed: flag({ docCount: 0, docName: [], failed: true }), none: flag({ docCount: 0, docName: [], failed: false }) }
  check('failed ≠ none（同为 0 篇，只有故障维不同）', shapes.failed !== shapes.none,
    `两者都是 ${shapes.failed}`)
  check('cited ≠ none', flag({ docCount: 1, docName: ['A.pdf'], failed: false }) !== shapes.none)
  check('用户消息不带检索状态', mapHistoryRecord({ id: 'y', role: 0, message: '问' })?.knowledgebase === undefined)
}

console.log('\n[4] ChatRobot.vue 走这一份映射（防止再内联一份副本）')
{
  const src = readFileSync(CHAT_ROBOT, 'utf8')
  check('ChatRobot.vue 引入 mapHistoryRecord', /from\s+'[^']*utils\/mapHistoryRecord'/.test(src))
  check('不再残留内联的角色映射分支', !/if\s*\(r\.role === 2\)\s*return\s*{\s*role:\s*'tool_call'/.test(src))
  check('两处历史加载都调用同一映射',
    (src.match(/\.map\(mapHistoryRecord\)/g) ?? []).length === 2,
    `实际 ${(src.match(/\.map\(mapHistoryRecord\)/g) ?? []).length} 处`)
}

console.log('\n[5] 前端声明必须对得上后端真实外发的形状（v2.68 立 · v2.72 随轨迹落库翻转）')
{
  // 工具轨迹三列 v2.72 起有真实写入点：外发恢复（NON_NULL），前端类型与映射分支两侧必须同源——
  // 任何一侧回滚都会红。
  const map = readFileSync(new URL(MAP_MOD), 'utf8')
  const sessionApi = readFileSync(SESSION_API, 'utf8')
  const admin = readFileSync(ADMIN_VIEW, 'utf8')
  check('映射处理 role 2/3 并引用 tool* 三列（轨迹落库后的读侧）',
    /role === 2/.test(map) && /role === 3/.test(map) && /toolName/.test(map))
  check('ChatRecordMessage 声明后端会外发的 tool* 字段',
    /toolName/.test(sessionApi) && /toolArgs/.test(sessionApi) && /toolResult/.test(sessionApi))
  check('用户表不渲染零写入路径的昵称/邮箱/手机号',
    !/昵称|邮箱|手机号|\.nickname|\.email|\.phone/.test(admin))
  // 反向锚点：只验"有"会让整块被误删也判绿，所以同时钉住"该有的还在"
  check('映射仍处理 role 0/1', /role === 0/.test(map) && /role === 1/.test(map))
  check('历史类型仍带检索状态', /knowledgebase\?: KnowledgebaseInfo/.test(sessionApi))
  check('用户表仍是三列且占位 colspan 与之一致',
    /colspan="3"/.test(admin) && /用户名/.test(admin) && !/colspan="6"[\s\S]{0,120}暂无用户/.test(admin))
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
