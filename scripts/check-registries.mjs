/**
 * 登记表与代码实况的一致性验证（v2.52 · C-117 · 候选 ㊱ 的门禁侧）。
 *
 * 这里锁的不是"文档写得好不好"，而是**文档里的每一条清单能不能被代码指向**：
 * 六组判据全部是"集合相等"或"逐项对应"，所以任何一侧单独漂移都会红——
 * 加了新 Kind 而没登记 ⇒ 红；登记了一个代码里没有的端点 ⇒ 红；
 * 新增 `check-*.mjs` 而没进 CI ⇒ 红。这正是 ㊱ 描述的失效形态：
 * 导读层与登记表此前**只靠人读**来保持一致，而人读这件事在批次节奏里必然漏。
 *
 * 与同目录其他脚本的口径一致：零依赖、不碰网络与库、只读源码文本与 markdown。
 * 运行：node scripts/check-registries.mjs
 */

import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, basename } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const read = rel => readFileSync(join(ROOT, rel), 'utf8')

/**
 * `--registry <路径>` 只换掉被检查的那份 markdown（代码侧仍读真实源码）：
 * 为的是能拿一份"故意漏登记"的副本验证判据真的会红，而不必去动受版本控制的文档。
 */
const argIdx = process.argv.indexOf('--registry')
const REGISTRY = argIdx >= 0 ? readFileSync(process.argv[argIdx + 1], 'utf8') : read('docs/REGISTRY.md')
const HTTP_INTERCEPTOR = read('backend/src/main/java/com/leyon/backend/interceptor/OpenApiAuthInterceptor.java')
const WS_INTERCEPTOR = read('backend/src/main/java/com/leyon/backend/interceptor/OpenApiWebSocketAuthInterceptor.java')
const METER = read('backend/src/main/java/com/leyon/backend/service/OpenApiDenialMeter.java')
const TOOL_REGISTRY = read('backend/src/main/java/com/leyon/backend/tool/ToolRegistry.java')
const LEDGER_UTIL = read('frontend/src/utils/denialLedger.ts')
const MIGRATE = read('scripts/db-migrate.sh')
const PKG = JSON.parse(read('frontend/package.json'))
const CI = read('.github/workflows/ci.yml')
const AGENTS = read('AGENTS.md')
const TOOL_DIR = 'backend/src/main/java/com/leyon/backend/tool'

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

/** 递归列出 main 源码树里的 .java（相对路径），用于"某个调用点全仓有几处"这类判据 */
function javaFiles(dir) {
  const out = []
  for (const entry of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
    const rel = `${dir}/${entry.name}`
    if (entry.isDirectory()) out.push(...javaFiles(rel))
    else if (entry.name.endsWith('.java')) out.push(rel)
  }
  return out
}

/** 取 `## N. 标题` 到下一个 `## ` 之间的正文（编号沿用 REGISTRY.md 的节号） */
function section(no) {
  const start = REGISTRY.indexOf(`\n## ${no}. `)
  if (start < 0) throw new Error(`REGISTRY.md 缺少第 ${no} 节`)
  const rest = REGISTRY.slice(start + 1)
  const next = rest.slice(1).search(/\n## \d+\. /)
  return next < 0 ? rest : rest.slice(0, next + 1)
}

const CN_NUM = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 }
const asNumber = token => (/^\d+$/.test(token) ? Number(token) : CN_NUM[token])

console.log('\n[1] 迁移账本：REGISTRY 第 4 节 ↔ 迁移目录 ↔ 应用方式')
{
  const s4 = section(4)
  const files = readdirSync(join(ROOT, 'backend/src/main/resources/db/migrations'))
    .filter(f => f.endsWith('.sql')).sort()
  for (const f of files) check(`${f} 已登记`, s4.includes(`\`${f}\``))
  const documented = [...s4.matchAll(/`(0\d{3}_[a-z0-9_]+\.sql)`/g)].map(m => m[1])
  check('文档没有登记已不存在的迁移文件（ghost 迁移会让上线步骤指错）',
    documented.every(f => files.includes(f)), documented.filter(f => !files.includes(f)).join(','))
  check('两侧数量相等', documented.length === files.length, `文档 ${documented.length} / 目录 ${files.length}`)
  check('迁移由脚本按文件名排序发现，而不是硬编码清单（硬编码＝新增迁移忘改脚本）',
    /find "\$MIG_DIR"[^\n]*-name '\*\.sql'[^\n]*\| sort/.test(MIGRATE))
  check('已应用版本记在 schema_migrations（幂等的前提）',
    /CREATE TABLE IF NOT EXISTS \\/.test(MIGRATE) && MIGRATE.includes('schema_migrations') && s4.includes('schema_migrations'))
}

console.log('\n[2] 开放端点 → 能力表：REGISTRY 第 2 节 ↔ REQUIRED_SCOPES ↔ 握手判定')
{
  const s2 = section(2)
  const coded = [...HTTP_INTERCEPTOR.matchAll(/new RequiredScope\("([^"]+)", ApiApp\.SCOPE_(\w+)\)/g)]
    .map(m => ({ path: m[1], scope: m[2].toLowerCase() }))
  check('代码侧确有登记项（解析失败时本门禁会静默全绿）', coded.length >= 2)
  for (const { path, scope } of coded) {
    const row = s2.split('\n').find(l => l.includes(path))
    check(`${path} 的能力与文档一致（文档要求 ${scope}）`, !!row && row.includes(`\`${scope}\``), row ?? '表内无此端点行')
  }
  const documentedPaths = [...s2.matchAll(/`(POST|WS) (\/api\/open\/[^`]+)`/g)]
    .map(m => `${m[1]} ${m[2]}`)
  check('文档侧没有代码里不存在的需能力端点',
    documentedPaths.every(entry =>
      coded.some(c => entry.endsWith(c.path)) || entry.includes('ws-voice')
      || entry.includes('{assistantId}')), documentedPaths.join(','))
  check('语音握手的 voice 判定在拦截器里（不靠 REST 侧兜）',
    WS_INTERCEPTOR.includes('ApiApp.SCOPE_VOICE') && s2.includes('SCOPE_VOICE'))
  check('未登记端点 fail-closed 的口径两侧一致',
    HTTP_INTERCEPTOR.includes('ENDPOINT_UNREGISTERED') && s2.includes('未登记的 `/api/open/` 路径按拒绝处理'))
  check('判定顺序：限流 → 验 Key → 验能力（反过来＝试 Key 预算不受约束）',
    WS_INTERCEPTOR.indexOf('tryAcquire') < WS_INTERCEPTOR.indexOf('authByApiKey')
      && WS_INTERCEPTOR.indexOf('authByApiKey') < WS_INTERCEPTOR.indexOf('SCOPE_VOICE')
      && s2.includes('限流 → 验 Key → 验能力'))
}

console.log('\n[3] 拒绝 Kind：REGISTRY 第 3 节 ↔ 枚举 ↔ 前端不抄清单')
{
  const s3 = section(3)
  const body = METER.slice(METER.indexOf('public enum Kind'))
  const enumBody = body.slice(0, body.indexOf(';'))
  const listed = [...enumBody.matchAll(/^\s+([A-Z][A-Z_]+)\("/gm)].map(m => m[1])
  check('枚举解析取到全部值（本行防正则失效）', listed.length >= 7, `解析到 ${listed.length}`)
  for (const k of listed) check(`Kind.${k} 已登记`, s3.includes(`\`${k}\``))
  const documented = [...s3.matchAll(/^\| `(KEY_|SCOPE_|ENDPOINT_|RATE_|URL_)[A-Z_]+`/gm)].map(m =>
    m[0].replace(/[`|]/g, '').trim())
  check('文档没有登记已不存在的 Kind（否则页面永远等不到这一格）',
    documented.every(k => listed.includes(k)), documented.filter(k => !listed.includes(k)).join(','))
  const countInText = asNumber((s3.match(/(\S)个枚举值/) || [])[1])
  check(`文档写的"× 个枚举值"＝实况 ${listed.length}`, countInText === listed.length, `读到 ${countInText}`)
  const cardinality = Number((s3.match(/基数上限 = (\d+) 种/) || [])[1])
  check(`基数上限的"× 种"＝枚举数（这一格撑不爆的结论依赖它）`, cardinality === listed.length, `读到 ${cardinality}`)
  check('前端不复制 Kind 清单（显示走后端下发的 kindLabel）',
    !listed.some(k => new RegExp(`['"\`]${k}['"\`]`).test(LEDGER_UTIL)))
}

console.log('\n[4] AI 工具表：REGISTRY 第 1 节 ↔ 各工具类的注册名')
{
  const s1 = section(1)
  const toolFiles = readdirSync(join(ROOT, TOOL_DIR)).filter(f => f.endsWith('Tool.java'))
  const names = []
  for (const f of toolFiles) {
    const src = read(join(TOOL_DIR, f))
    for (const m of src.matchAll(/\.builder\("([a-z_]+)"/g)) names.push({ name: m[1], cls: f })
  }
  check('代码侧确有工具注册名（解析失效时本门禁会静默全绿）', names.length >= 9, `解析到 ${names.length}`)
  for (const { name, cls } of names) {
    const row = s1.split('\n').find(l => l.includes(`\`${name}\``))
    check(`工具 ${name} 已登记且类名对得上（${cls}）`, !!row && row.includes(`\`${basename(cls, '.java')}\``),
      row ?? '表内无此工具行')
  }
  const documented = [...s1.matchAll(/^\| `([a-z_]+)`/gm)].map(m => m[1])
  check('文档没有登记代码里不存在的工具（前端勾选框会出现假能力）',
    documented.every(n => names.some(x => x.name === n)), documented.filter(n => !names.some(x => x.name === n)).join(','))
  check('工具重名启动即失败（口径来自文档，判据来自代码）',
    /IllegalStateException/.test(TOOL_REGISTRY) && s1.includes('工具重名启动即失败'))
}

console.log('\n[5] 门禁台账：scripts/ ↔ REGISTRY 第 6 节 ↔ package.json ↔ ci.yml ↔ AGENTS 命令块')
{
  const s6 = section(6)
  const scripts = readdirSync(join(ROOT, 'scripts')).filter(f => /^check-.*\.(py|mjs)$/.test(f)).sort()
  for (const f of scripts) check(`${f} 在门禁台账里有一行`, s6.includes(`\`${f}\``))
  const documentedScripts = [...s6.matchAll(/`(check-[a-z-]+\.(?:py|mjs))`/g)].map(m => m[1])
  check('台账没有登记已删除的脚本', documentedScripts.every(f => scripts.includes(f)),
    documentedScripts.filter(f => !scripts.includes(f)).join(','))
  const npmChecks = Object.keys(PKG.scripts ?? {}).filter(k => k.startsWith('check:'))
  check('每个 check:* 都指向真实存在的脚本文件',
    npmChecks.every(k => {
      const target = PKG.scripts[k].match(/(check-[a-z-]+\.mjs)/)
      return target && scripts.includes(target[1])
    }))
  const inCi = npmChecks.filter(k => CI.includes(`npm run ${k}`))
  check('全部 check:* 已纳入 CI（未纳 CI 的检查＝只有人手工跑时才生效）',
    inCi.length === npmChecks.length, npmChecks.filter(k => !inCi.includes(k)).join(','))
  const wiredToCi = new Set(npmChecks.filter(k => CI.includes(`npm run ${k}`))
    .map(k => (PKG.scripts[k].match(/(check-[a-z-]+\.mjs)/) || [])[1]).filter(Boolean))
  for (const py of ['check-docs.py', 'check-config.py']) if (CI.includes(py)) wiredToCi.add(py)
  const claimed = s6.split('\n').filter(l => /^\| `check-[a-z-]+\.\w+`.*✅/.test(l))
    .map(l => (l.match(/`(check-[a-z-]+\.\w+)`/) || [])[1]).filter(Boolean)
  check('台账标 ✅ 的脚本都真的在 ci.yml 里跑（标了却没进＝谎报覆盖面）',
    claimed.every(f => wiredToCi.has(f)), claimed.filter(f => !wiredToCi.has(f)).join(','))
  check('ci.yml 里跑的每道检查都在台账标了 ✅（做了却没登记＝下次没人知道它在）',
    [...wiredToCi].every(f => claimed.includes(f)), [...wiredToCi].filter(f => !claimed.includes(f)).join(','))
  const stubCount = npmChecks.length - (npmChecks.includes('check:auth') ? 1 : 0)
  const ciClaim = asNumber((CI.match(/共([一二三四五六七八九十\d]+)道/) || [])[1])
  check(`ci.yml 注释的"共×道桩测"＝npm 侧 check:* 去掉 check:auth = ${stubCount}`,
    ciClaim === stubCount, `注释读到 ${ciClaim}`)
  const agentsLoop = (AGENTS.match(/for c in ([^;]+); do/) || [])[1]
  const agentsChecks = agentsLoop ? agentsLoop.trim().split(/\s+/) : []
  check('AGENTS 的提交前命令块逐个点名了全部 check:*（漏一个就会在 CI 上首次变红）',
    agentsChecks.length === npmChecks.length
      && npmChecks.every(k => agentsChecks.includes(k.replace('check:', '').replace(':auth', 'auth'))),
    agentsChecks.join(','))
  check('smoke.sh 在 CI 只做语法检查（真机冒烟刻意不进关键路径）',
    CI.includes('bash -n') && s6.includes('只做 `bash -n` 语法检查'))
}

console.log('\n[6] 知识库本地授权登记表：写入面只能有一处（v2.53 · C-118/C-119）')
{
  const CONTROLLER_DIR = 'backend/src/main/java/com/leyon/backend/controller'
  const KB_SERVICE_FILE = 'backend/src/main/java/com/leyon/backend/service/KnowledgeBaseService.java'
  const KB_SERVICE = read(KB_SERVICE_FILE)
  // 这张表是 /api/ragflow 唯一的授权依据（转发用一把共享 API Key），所以"谁能写它"＝"谁能定义谁拥有知识库"。
  // v2.53 删掉的 POST /api/knowledges 正是让任意登录用户自带 datasetId 写一行，真机实测：
  // 植入一行后 documents 由 403 变 500、retrieval-test 由 403 变 200。
  check('KnowledgeBaseController 没有回来（授权表不再有余地接受调用方指定的 datasetId）',
    !readdirSync(join(ROOT, CONTROLLER_DIR)).includes('KnowledgeBaseController.java'))
  const writers = javaFiles('backend/src/main/java')
    .filter(f => f !== KB_SERVICE_FILE && read(f).includes('knowledgeBaseService.create('))
  check('全仓唯一写入点是 RAGFlow 创建回执（datasetId 来自上游响应，不来自请求体）',
    writers.length === 1 && writers[0].endsWith('RagflowProxyController.java'), writers.join(','))
  const writes = KB_SERVICE.match(/knowledgeBaseMapper\.(insert|update\w*|delete)\(/g) ?? []
  check('KnowledgeBaseService 只剩一条写语句（insert），读侧判定里不夹带写口',
    writes.length === 1 && writes[0].includes('insert'), writes.join(','))
  const MIG_DIR = 'backend/src/main/resources/db/migrations'
  const MIG_NAME = '0007_kb_dataset_unique.sql'
  // 0007 被删掉正是本组要拦的回归之一，所以先列目录再读：缺文件要报成一条具名 FAIL，不能是 ENOENT 堆栈
  const MIGRATION = readdirSync(join(ROOT, MIG_DIR)).includes(MIG_NAME) ? read(`${MIG_DIR}/${MIG_NAME}`) : ''
  check('0007 两步齐全：先 yunyu_assert 拦住冲突行，再建 uk_kb_dataset_id',
    MIGRATION.includes('yunyu_assert') && MIGRATION.includes('uk_kb_dataset_id'))
  check('index.sql（新环境建表）与迁移同一口径，否则新库天生没有这条约束',
    read('backend/src/main/resources/index.sql').includes('UNIQUE KEY `uk_kb_dataset_id`'))
  const SMOKE = read('scripts/smoke.sh')
  check('冒烟保留 /api/knowledges 的两条 404 锚点（悄悄删掉锚点＝下次加回来无人知晓）',
    SMOKE.includes('GET /api/knowledges 已下线') && SMOKE.includes('POST /api/knowledges 已下线'))
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
