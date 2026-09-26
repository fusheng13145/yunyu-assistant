/**
 * 对话里"知识库检索失败"与"没查到内容"的可区分性验证（C-88 的前端侧）。
 *
 * 后端 v2.39 已在 query_end 帧里给出 knowledgebase.failed；此脚本锁定前端把这一帧
 * 翻译成了哪种角标。口径与 check-auth-session.mjs / check-notification.mjs 一致：
 * 无测试框架，用 Node 的 TS 类型剥离直接 import 生产模块（import type 会被剥离，
 * 所以模块可以引用 ../types）。
 * 运行：node scripts/check-knowledgebase-flag.mjs
 */

const MOD = new URL('../frontend/src/utils/knowledgebaseFlag.ts', import.meta.url).href

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

const { knowledgebaseFlag } = await import(MOD)

console.log('\n[1] 外部故障必须与"知识库没答案"分家')
{
  check('failed=true 且无文档 → failed（后端故障的典型形状）',
    knowledgebaseFlag({ failed: true, docCount: 0, docName: [] }) === 'failed')
  check('failed 优先于文档数（故障时即便带了脏数据也按故障报）',
    knowledgebaseFlag({ failed: true, docCount: 3, docName: ['a.pdf'] }) === 'failed')
  check('只有 failed=true、没有 docName → 仍是 failed',
    knowledgebaseFlag({ failed: true }) === 'failed')
}

console.log('\n[2] 检索成功但无命中：不是失败，也不吹"引用了 N 篇"')
{
  check('code=0 + 空结果 → none', knowledgebaseFlag({ failed: false, docCount: 0, docName: [] }) === 'none')
  check('缺省 failed（历史消息没有这个字段）→ none', knowledgebaseFlag({ docCount: 0 }) === 'none')
  check('undefined（旧数据整段缺失）→ none', knowledgebaseFlag(undefined) === 'none')
  check('null → none', knowledgebaseFlag(null) === 'none')
}

console.log('\n[3] 命中时显示引用角标')
{
  check('有 docCount → cited', knowledgebaseFlag({ failed: false, docCount: 2 }) === 'cited')
  check('只有 docName（后端个别路径没带 count）→ cited',
    knowledgebaseFlag({ docName: ['售后手册.pdf'] }) === 'cited')
}

console.log('\n[4] 口径边界：0 与 false 不能被当成失败')
{
  check('docCount=0 且 failed=false 绝不产出 failed',
    knowledgebaseFlag({ failed: false, docCount: 0, docName: [] }) !== 'failed')
  check('docName 空数组不产出 cited', knowledgebaseFlag({ docName: [] }) === 'none')
  check('返回值只会是三态之一',
    ['failed', 'cited', 'none'].includes(knowledgebaseFlag({ failed: true, docCount: 1 })))
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
