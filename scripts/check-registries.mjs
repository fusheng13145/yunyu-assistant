/**
 * 登记表与代码实况的一致性验证（v2.52 · C-117 · 候选 ㊱ 的门禁侧）。
 *
 * 这里锁的不是"文档写得好不好"，而是**文档里的每一条清单能不能被代码指向**：
 * 十四组判据全部是"集合相等"、"逐项对应"或"唯一入口"，所以任何一侧单独漂移都会红——
 * 加了新 Kind 而没登记 ⇒ 红；登记了一个代码里没有的端点 ⇒ 红；
 * 新增 `check-*.mjs` 而没进 CI ⇒ 红；新增的门禁缺规范退出码行 ⇒ 红；配额上界出现第二份字面量 ⇒ 红。
 * 这正是 ㊱ 描述的失效形态：导读层与登记表此前**只靠人读**来保持一致，而人读这件事在批次节奏里必然漏。
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

console.log('\n[7] 助手级成本参数：清单与钳制都只能有一处（v2.54 · C-120/C-121）')
{
  const SRC = 'backend/src/main/java/com/leyon/backend'
  const POLICY = read(`${SRC}/service/AssistantPolicy.java`)
  const CATALOG = read(`${SRC}/service/ModelCatalog.java`)
  const SVC = read(`${SRC}/service/AssistantService.java`)
  const CTRL = read(`${SRC}/controller/AssistantController.java`)
  const MODELS_CTRL = read(`${SRC}/controller/ModelController.java`)
  const ROBOT = read('frontend/src/views/SmartRobot.vue')
  const SMOKE = read('scripts/smoke.sh')
  const ASSEMBLIES = [`${SRC}/handler/ChatWebSocketHandler.java`,
    `${SRC}/handler/VoiceSignalingHandler.java`, `${SRC}/controller/OpenApiChatController.java`]

  // 模型清单同时是"界面能选什么"和"后端放行什么"。分成两份时的失效形态不是报错，
  // 而是选得到却用不了（清单外值被读侧静默回落成默认模型，用户以为换了这个模型在跑）。
  const catalogIds = [...CATALOG.matchAll(/new ModelInfo\("([^"]+)"/g)].map(m => m[1])
  check('清单解析到 ≥4 项（正则失效时本组会静默全绿）', catalogIds.length >= 4, `解析到 ${catalogIds.length}`)
  const literals = javaFiles('backend/src/main/java').filter(f => /new ModelInfo\(/.test(read(f)))
  check('ModelInfo 字面量只在 ModelCatalog 构造（其余位置一律转发同一份）',
    literals.length === 1 && literals[0].endsWith('ModelCatalog.java'), literals.join(','))
  check('ModelController 下发的是清单本身，不是第二份字面量',
    MODELS_CTRL.includes('ModelCatalog') && !/new ModelInfo\(/.test(MODELS_CTRL))
  check('前端不复制模型 id（选项全部由 /api/models 下发）',
    !catalogIds.some(id => ROBOT.includes(id)))
  // 冒烟的两个模型 id 是写死在脚本里的：清单调整时这一条先红，而不是等到 §3 整节塌掉才发现
  const defaultModel = (SMOKE.match(/SMOKE_MODEL:-([a-z0-9-]+)/) || [])[1]
  check('冒烟创建助手的默认模型在清单内（否则 §3 起后面每一节都拿不到助手）',
    !!defaultModel && catalogIds.includes(defaultModel), defaultModel ?? '未解析到')
  const smokeModel = (SMOKE.match(/modelName '([a-z0-9-]+)' temperature/) || [])[1]
  check('冒烟 §3.5 正向锚点用的模型在清单内',
    !!smokeModel && catalogIds.includes(smokeModel), smokeModel ?? '未解析到')

  // 唯一判据入口：任何一处直接读实体值，就是绕过钳制（与 ClientIpResolver、accessGranted 同形）
  const RAW = ['getModelName()', 'getPersonality()', 'getMaxTokens()', 'getTemperature()']
  const rawSites = javaFiles('backend/src/main/java').filter(f =>
    !f.endsWith('AssistantPolicy.java') && !f.includes('/entity/Assistant.java')
    && RAW.some(g => read(f).includes(g)))
  check('除 AssistantPolicy 与实体自身外，全仓无人裸读四项成本参数',
    rawSites.length === 0, rawSites.join(','))
  for (const f of ASSEMBLIES) {
    const src = read(f)
    check(`${basename(f)} 由 runtime() 取钳制后的参数装配`,
      src.includes('assistantPolicy.runtime(assistant)')
      && src.includes('setModelParams(runtime.model(), runtime.temperature(), runtime.maxTokens())')
      && !/setModelParams\(\s*assistant\./.test(src))
  }

  const runtimeBody = POLICY.slice(POLICY.indexOf('public Runtime runtime'),
    POLICY.indexOf('public void validateForWrite'))
  check('读侧入口内不抛异常（装配发生在回合进行中，拒绝＝把已有脏数据的助手整体冻住）',
    !/throw\s+new/.test(runtimeBody))
  const clamps = (SVC.match(/assistantPolicy\.clampForStorage\(/g) ?? []).length
  check('AssistantService 的 create/update 两条写路径都过落库前钳制', clamps === 2, `解析到 ${clamps}`)
  const validates = (CTRL.match(/assistantPolicy\.validateForWrite\(/g) ?? []).length
  check('AssistantController 的 POST/PUT 两条写路径都过写侧拒绝', validates === 2, `解析到 ${validates}`)
  const postBody = CTRL.slice(CTRL.indexOf('@PostMapping'), CTRL.indexOf('@GetMapping'))
  check('POST 侧拒绝先于建库写入（校验失败不消耗助手配额、不留半行）',
    postBody.indexOf('validateForWrite') >= 0 && postBody.indexOf('validateForWrite') < postBody.indexOf('assistantService.create('))
  const putBody = CTRL.slice(CTRL.indexOf('@PutMapping'))
  check('PUT 侧鉴权排在参数校验之前（越权者问不出"这个值合不合法"）',
    putBody.indexOf('requireManage') >= 0 && putBody.indexOf('requireManage') < putBody.indexOf('validateForWrite'))
  const maxTokens = Number((POLICY.match(/MAX_OUTPUT_TOKENS = (\d+)/) || [])[1])
  const minTemp = Number((POLICY.match(/MIN_TEMPERATURE = ([\d.]+)/) || [])[1])
  const maxTemp = Number((POLICY.match(/MAX_TEMPERATURE = ([\d.]+)/) || [])[1])
  check('上限常量解析有效（>0 的温度域才是"有边界"）',
    maxTokens > 0 && minTemp === 0 && maxTemp === 2, `tokens=${maxTokens} temp=${minTemp}~${maxTemp}`)
  // 界面上的 min/max 是用户读到"能填什么"的地方，后端常量是"实际放行什么"；分叉的表象是填得进却报错
  const uiBounds = [...ROBOT.matchAll(/min="(-?[\d.]+)"\s+max="(-?[\d.]+)"/g)].map(m => [Number(m[1]), Number(m[2])])
  check('两处表单的温度输入边界与后端常量逐项相等',
    uiBounds.length === 2 && uiBounds.every(([lo, hi]) => lo === minTemp && hi === maxTemp),
    uiBounds.map(b => b.join('~')).join(' | '))
  // §3.5 与 §8.1 各自锁自己的判据，所以按区段取而不是全文计数——否则加一条别处的 want_param 会假装"§3.5 漂移"。
  // 前导空白要允许：PUT 侧那条在 `if [ -n "$ASSISTANT_ID" ]` 块内是缩进的，只认行首会把漏登记的余量留给它
  const region35 = SMOKE.slice(SMOKE.indexOf("section '3.5"), SMOKE.indexOf("section '4. 会话与消息'"))
  const paramRejects = (region35.match(/^\s*want_param /gm) ?? []).length
  check('冒烟 §3.5 的五条写侧拒绝锚点在位（四条 POST 越界 + 一条 PUT 越界）',
    paramRejects === 5, `解析到 ${paramRejects}`)
  check('拒绝判据落在业务码而不是 HTTP 状态（paramError 的 HTTP 仍是 200，只看 status 会永远绿）',
    /want_param\(\) \{[\s\S]*?"400"/.test(SMOKE))
  check('冒烟带反向锚点（合法值须原样读回，否则"一律钳到默认"也能全绿）',
    SMOKE.includes('合法 maxTokens 未被钳到上限或默认') && SMOKE.includes('越界 PUT 未改动库内值'))
  check('§3.5 区段标题在位（区段号是冒烟报数口径的一部分）',
    SMOKE.includes("section '3.5 助手成本参数越界拒绝（不进库）'"))
  // v2.54 冒烟实测撞出的既有脆弱写法（C-122）：三条 result=0 的审计行常落在同一秒，
  // 而管理端 `ORDER BY created_at DESC` 对同秒行不设二级排序 ⇒ "取扫到的第一条原因去匹配非属主那句"
  // 会随机红。修复方向是逐条都在，写法不能悄悄退回"只看第一条"。
  check('拒绝原因断言按"三条各自点名"判定，不是取排序第一的那条',
    SMOKE.includes('REJECT_REASONS') && !SMOKE.includes('REJECT_DETAIL'))
}

console.log('\n[8] 配额数值边界：判据入口与关闭语义都只能有一处（v2.57 · C-124）')
{
  const SRC = 'backend/src/main/java/com/leyon/backend'
  const QP_FILE = `${SRC}/service/QuotaPolicy.java`
  const QP = read(QP_FILE)
  const SVC = read(`${SRC}/service/QuotaService.java`)
  const CTRL = read(`${SRC}/controller/AdminController.java`)
  const ADMIN_VUE = read('frontend/src/views/Admin.vue')
  const SMOKE = read('scripts/smoke.sh')

  // 配额四项数值直接决定真实开销，而 `limit <= 0` 在运行时被解释成"直接拒绝"。
  // 此前只有界面半边有判据（Admin.vue 挡负数），服务端全链透传 ⇒ curl 能把一个作用域静默锁死，
  // 且被拦用户看到的是"请明日再试"（对"被关闭"的维度是错误指引）。
  // 上界要写成算式（24 * 60 * 60）才带得走判据，所以这里按乘法求值而不是 Number()
  const exprValue = expr => expr.split('*').reduce((acc, t) => acc * Number(t.replace(/_/g, '').trim()), 1)
  const bounds = ['MAX_ASSISTANT_LIMIT', 'MAX_DAILY_CALL_LIMIT', 'MAX_DAILY_CALL_SEC_LIMIT', 'MAX_DAILY_MSG_LIMIT']
    .map(n => exprValue((QP.match(new RegExp(`${n} = ([\\d_ *]+);`)) || [])[1] ?? 'NaN'))
  check('四项上界常量解析有效（正则失效时本组会静默全绿）',
    bounds.every(v => Number.isFinite(v) && v > 0), bounds.join(','))
  const minLimit = Number((QP.match(/MIN_LIMIT = (\d+)/) || [])[1])
  check('下界统一为 0（0＝关闭该维度是既有口径，负数没有语义）', minLimit === 0, `读到 ${minLimit}`)
  // 时长上界要写成算式而不是 86400：判据是"一天的秒数"，魔法数会让下次没人知道能不能调
  check('时长上界写成 24 * 60 * 60（判据是一天的总秒数，不是魔法数）',
    /MAX_DAILY_CALL_SEC_LIMIT = 24 \* 60 \* 60;/.test(QP))
  const declSites = javaFiles('backend/src/main/java').filter(f =>
    /MAX_(ASSISTANT|DAILY_CALL|DAILY_CALL_SEC|DAILY_MSG)_LIMIT\s*=/.test(read(f)))
  check('上界常量只在 QuotaPolicy 声明（第二份字面量＝两处会各自漂移）',
    declSites.length === 1 && declSites[0] === QP_FILE, declSites.join(','))
  const dupBounds = javaFiles('backend/src/main/java')
    .filter(f => f !== QP_FILE && /100_000|100000|86400/.test(read(f)))
  check('后端其余源码不复制配额上界数值', dupBounds.length === 0, dupBounds.join(','))
  check('前端不复制配额上界（界面只挡负数与非整数，放行什么由服务端说）',
    !/86400|100_000|100000/.test(ADMIN_VUE))
  check('界面把"0＝关闭不随次日恢复"讲给管理员（只说"明天重置"会让关闸看起来像临时措施）',
    ADMIN_VUE.includes('不随之恢复'))

  const validates = (CTRL.match(/quotaPolicy\.validateForWrite\(/g) ?? []).length
  check('AdminController 恰一次写侧校验（POST/PUT 合并成一个 upsert，少一处＝有条路不校验）',
    validates === 1, `解析到 ${validates}`)
  // 只在该方法体内比次序：文件里更靠前的 GET /quotas 本来就先读库，拿全文索引会永远判成"校验在后"
  const upsert = CTRL.slice(CTRL.indexOf('@PutMapping("/quotas")'))
  const firstDbAction = Math.min(...['quotaMapper.', 'quotaService.']
    .map(p => upsert.indexOf(p)).filter(i => i >= 0))
  check('校验先于 upsert 路径内的任何库动作（越界值一旦进库就参与运行时判定）',
    upsert.indexOf('quotaPolicy.validateForWrite') >= 0 && firstDbAction > 0
      && upsert.indexOf('quotaPolicy.validateForWrite') < firstDbAction, `首个库动作在 ${firstDbAction}`)

  const disabledCalls = (SVC.match(/QuotaPolicy\.disabledMessage\(/g) ?? []).length
  check('QuotaService 的三处"上限为 0"分支都走同一关闭文案入口', disabledCalls === 3, `解析到 ${disabledCalls}`)
  const disabledLiterals = javaFiles('backend/src/main/java').filter(f =>
    f !== QP_FILE && read(f).includes('已由管理端关闭'))
  check('关闭文案只在 QuotaPolicy 拼（在别处重写一句就会与写侧提示分叉）',
    disabledLiterals.length === 0, disabledLiterals.join(','))
  check('关闭语义与"用满了明天再来"在文案上分家（一侧讲不会自动恢复，一侧保留明日重置）',
    QP.includes('不会自动恢复') && SVC.includes('请明日再试'))

  // 冒烟 §8.1 的逐值读回之所以确定，靠的是 quotas 的唯一约束；约束没了，"取第一行"就重新变成 C-122 那种随机红
  check('index.sql 有 uk_quota_scope（冒烟逐值断言"哨兵作用域至多一行"的前提）',
    read('backend/src/main/resources/index.sql').includes('UNIQUE KEY `uk_quota_scope`'))
  const region81 = SMOKE.slice(SMOKE.indexOf('# ---- 8.1 配额写入侧边界'), SMOKE.indexOf("skip 'POST /api/admin/archive/run'"))
  check('冒烟 §8.1 区段在位（区段号是冒烟报数口径的一部分）', region81.length > 200, `取到 ${region81.length} 字`)
  // §8.1 整段在管理端登录成功的 if 块内，want_param 是缩进的，所以这里允许前导空白
  const sentinelRejects = (region81.match(/^\s*want_param /gm) ?? []).length
  check('§8.1 的两条写侧拒绝锚点在位', sentinelRejects === 2, `解析到 ${sentinelRejects}`)
  check('§8.1 用哨兵作用域而不是真实用户（越界行一旦生效会干扰本轮其余断言）',
    region81.includes("Q_SENTINEL='smoke-quota-sentinel'"))
  check('§8.1 带反向锚点（0 与合法值必须原样落库，否则"一律改回兜底值"的实现也能全绿）',
    region81.includes('关闭值 0 原样落库') && region81.includes('合法值 1234 原样落库'))
  check('§8.1 锁住"越界值没进库"与"超出整型域不许退化成 500"',
    region81.includes('越界值进了库') && region81.includes('库内值逐字段不变') && region81.includes('超出整型域'))
  // 判据必须写成"值没变"而不是"哨兵行不存在"：冒烟可重复跑，第二轮起上一轮的合法行就在库里（v2.57 真机踩过）
  check('§8.1 的判据不依赖"库里本来没有哨兵行"（重复跑必须结论稳定）',
    !region81.includes('哨兵作用域不存在'))

  const qpTests = (read('backend/src/test/java/com/leyon/backend/service/QuotaPolicyTest.java')
    .match(/^\s+@Test/gm) ?? []).length
  check('QuotaPolicyTest 逐项覆盖四项边界（≥9 例）', qpTests >= 9, `解析到 ${qpTests}`)
  const ctrlTest = read('backend/src/test/java/com/leyon/backend/controller/AdminControllerTest.java')
  check('管理端单测锁住"越界回 400 且一次库动作都不发"',
    /verify\(quotaMapper, never\(\)\)\.selectOne/.test(ctrlTest) && /verify\(quotaMapper, never\(\)\)\.insert/.test(ctrlTest))
}

console.log('\n[9] 知识库可见性求交：三条对话通道共用同一判据单点（v2.58 · C-125 · 候选 ㊷）')
{
  const SRC = 'backend/src/main/java/com/leyon/backend'
  const KB_FILE = `${SRC}/service/KnowledgeBaseService.java`
  const KB = read(KB_FILE)
  const CHANNELS = [`${SRC}/handler/ChatWebSocketHandler.java`,
    `${SRC}/handler/VoiceSignalingHandler.java`, `${SRC}/controller/OpenApiChatController.java`]
  const TEST_DIR = 'backend/src/test/java/com/leyon/backend'

  const defs = javaFiles('backend/src/main/java')
    .filter(f => /List<String> retainVisibleDatasetIds\(/.test(read(f)))
  check('求交判据恰有一处定义（两处定义＝一条通道会漏，㊷ 的成因）',
    defs.length === 1 && defs[0] === KB_FILE, defs.join(','))

  const body = KB.slice(KB.indexOf('public List<String> retainVisibleDatasetIds('),
    KB.indexOf('public boolean canManageDataset'))
  const shortCircuit = body.indexOf('return List.of()')
  check('空输入短路排在查库之前（无知识库的助手不该为判定付一次查询）',
    shortCircuit >= 0 && shortCircuit < body.indexOf('listVisibleDatasetIds'), `短路在 ${shortCircuit}`)
  check('丢弃计数 WARN 落在判据内部（否则新通道会静默吞掉越权数据集）',
    /logger\.warn\([^)]*不可见/s.test(body))
  const warnLiterals = javaFiles('backend/src/main/java').filter(f =>
    f !== KB_FILE && read(f).includes('不可见，已按可见范围收敛'))
  check('WARN 文案不在调用方各写一份', warnLiterals.length === 0, warnLiterals.join(','))

  for (const f of CHANNELS) {
    const src = read(f)
    check(`${basename(f)} 把数据集交给 ChatService 前先过同一判据`,
      src.includes('knowledgeBaseService.retainVisibleDatasetIds(')
        && !/new ChatService\([\s\S]{0,200}parseKnowledgeIds\(/.test(src))
  }
  const wrappers = javaFiles('backend/src/main/java').filter(f => read(f).includes('retainVisibleKnowledgeIds('))
  check('调用方私有包装归零（包装就是"第二条通道各改各的"的入口）',
    wrappers.length === 0, wrappers.join(','))
  const callSites = javaFiles('backend/src/main/java')
    .flatMap(f => Array.from(read(f).matchAll(/knowledgeBaseService\.retainVisibleDatasetIds\(/g)).map(() => f))
  check('装配侧调用点解析到 4 处（三通道装配 + 文本 WS 的运行中改选）',
    callSites.length === 4, `解析到 ${callSites.length}`)

  // ㊷ 的三条用例名是判据的鉴别力所在：悄悄删掉任一条，本行先红而不是等下一次越权读取
  const OPEN_TEST = read(`${TEST_DIR}/controller/OpenApiChatControllerTest.java`)
  check('开放通道单测锁住"只有可见子集进检索"与"按调用方判定"',
    OPEN_TEST.includes('chat_onlyVisibleDatasetsReachRetrieval')
      && OPEN_TEST.includes('chat_visibilityJudgedAgainstCallerRatherThanAssistantOwner'))
  const WS_TEST = read(`${TEST_DIR}/handler/ChatWebSocketHandlerTest.java`)
  const VOICE_TEST = read(`${TEST_DIR}/handler/VoiceSignalingHandlerTest.java`)
  check('两条内部通道的求交用例仍在（回归会以"某条通道不再收敛"的形态复活）',
    WS_TEST.includes('selectedKbIds_areIntersectedWithVisibleDatasets')
      && VOICE_TEST.includes('offer_dropsKnowledgeDatasetsInvisibleToCaller'))
  const KB_TEST = read(`${TEST_DIR}/service/KnowledgeBaseServiceTest.java`)
  const kbTests = (KB_TEST.match(/^\s+@Test/gm) ?? []).length
  check('判据自身有单测覆盖（≥13 例：可见集 + 归属闸门 + 求交契约）', kbTests >= 13, `解析到 ${kbTests}`)

  // 写入侧不做可见性校验是本批刻意保留的口径（会改变助手保存行为）；漂移要先经过这里
  const writeSide = [`${SRC}/service/AssistantService.java`, `${SRC}/controller/AssistantController.java`]
    .filter(f => read(f).includes('retainVisibleDatasetIds'))
  check('助手保存侧仍不做可见性校验（要做需先立口径，不许顺手改写入行为）',
    writeSide.length === 0, writeSide.join(','))
}

console.log('\n[10] 通话时长配额：发起前与回合边界共用同一判据，通话中不烧次数（v2.59 · C-126 · 方案 A）')
{
  const SRC = 'backend/src/main/java/com/leyon/backend'
  const QS_FILE = `${SRC}/service/QuotaService.java`
  const QS = read(QS_FILE)
  const VOICE_FILE = `${SRC}/handler/VoiceSignalingHandler.java`
  const VOICE = read(VOICE_FILE)
  const TEST_DIR = 'backend/src/test/java/com/leyon/backend'

  const defs = javaFiles('backend/src/main/java').filter(f => /void checkOngoingCallSec\(/.test(read(f)))
  check('通话中时长复核判据恰一处定义（两处＝一条通路会漏，成因同 ㊷）',
    defs.length === 1 && defs[0] === QS_FILE, defs.join(','))
  // 时长维度有"被关闭"和"用满了"两种拒绝，用户动作不同（找管理员 / 等明天），所以两个判据各自只许一个出口
  const disabledDefs = javaFiles('backend/src/main/java')
    .filter(f => /private void rejectIfCallSecDisabled\(/.test(read(f)))
  check('时长"关闭"判据只在 QuotaService 有一个出口',
    disabledDefs.length === 1 && disabledDefs[0] === QS_FILE, disabledDefs.join(','))
  const disabledCalls = (QS.match(/rejectIfCallSecDisabled\(quota\);/g) ?? []).length
  check('发起前与通话中两处都调用同一"关闭"判据', disabledCalls === 2, `解析到 ${disabledCalls}`)
  const overCalls = (QS.match(/rejectIfCallSecOver\(quota, /g) ?? []).length
  check('发起前与通话中两处都调用同一"越界"判据', overCalls === 2, `解析到 ${overCalls}`)
  const overLiterals = javaFiles('backend/src/main/java')
    .filter(f => read(f).includes('单日通话时长已达上限（'))
  check('越界文案只拼一处（第二份会在两个入口给出不同的分钟数）',
    overLiterals.length === 1 && overLiterals[0] === QS_FILE, overLiterals.join(','))
  check('前端不复制时长越界文案', !read('frontend/src/views/Admin.vue').includes('单日通话时长已达上限'))

  const ongoing = QS.slice(QS.indexOf('public void checkOngoingCallSec('),
    QS.indexOf('private void rejectIfCallSecDisabled('))
  // 本批的修法之所以能逐轮复用，关键就是它只读不扣：一旦顺手接上扣减，回合会把次数配额当秒表烧掉
  check('通话中复核体内不出现任何扣减调用',
    !/consumeOrThrow|consumeDaily|updateDailyUsage/.test(ongoing))
  check('通话中的用量＝当日已结算秒 + 本通已活秒数',
    ongoing.includes('sumCallSecSince(') && ongoing.includes('liveCallSecSince(callId, todayStart)'))
  check('跨零点的通话只计今日那一段（昨夜那部分属于昨天的配额日）',
    /isBefore\(todayStart\)\s*\?\s*todayStart/.test(QS))
  check('通话ID 缺失时退化为"只判已结算量"，不拿空 ID 去查库',
    /if \(!StringUtils\.hasText\(callId\)\)\s*\{\s*return 0;/.test(QS))

  const asr = VOICE.slice(VOICE.indexOf('private void handleAsrResult('),
    VOICE.indexOf('public void onVoiceResponseComplete'))
  const iScope = asr.indexOf('openApiVoiceStillAllowed(session)')
  const iSec = asr.indexOf('quotaService.checkOngoingCallSec(')
  const iMsg = asr.indexOf('quotaService.checkSendMessage(')
  check('回合边界三道复核的次序固定：能力 → 时长 → 消息（时长排在扣减之后会白烧一条消息额度）',
    iScope >= 0 && iSec > iScope && iMsg > iSec, `能力=${iScope} 时长=${iSec} 消息=${iMsg}`)
  check('时长复核传入本通通话ID（不传就只能看到已结算量，等于没修）',
    asr.includes('checkOngoingCallSec(userId, sessionCallRecordMap.get(sessionId))'))
  const secBlock = asr.slice(iSec, iMsg)
  const iFrame = secBlock.indexOf('MSG_TYPE_QUERY_END')
  const iTts = secBlock.indexOf('sendTTS')
  const iClose = secBlock.indexOf('closeSession(session)')
  check('时长用尽的收场是"送原因帧 → 播报 → 挂断"（通话里读不到帧，只发帧等于没告知）',
    iFrame >= 0 && iTts > iFrame && iClose > iTts, `帧=${iFrame} TTS=${iTts} 挂断=${iClose}`)
  check('回合边界不改用 checkStartCall（逐轮扣次数是错误修法）', !asr.includes('checkStartCall('))

  const startSites = javaFiles('backend/src/main/java')
    .filter(f => f !== QS_FILE && read(f).includes('checkStartCall('))
  check('发起侧判定仍是两处（通话创建 + 开放外呼），第三处出现＝有通路被逐轮复用',
    startSites.length === 2, startSites.join(','))

  const QT = read(`${TEST_DIR}/service/QuotaServiceTest.java`)
  const V_TEST = read(`${TEST_DIR}/handler/VoiceSignalingHandlerTest.java`)
  const ANCHORS = ['ongoingCallSec_countsLiveCallSecondsAndThrows',
    'ongoingCallSec_settledPlusLiveStillWithinLimitPasses', 'ongoingCallSec_neverBurnsDailyCallCount',
    'ongoingCallSec_zeroLimitReportsDisabledNotTomorrow',
    'ongoingCallSec_countsOnlyTodayPartOfCrossMidnightCall',
    'ongoingCallSec_withoutCallIdFallsBackToSettledOnly']
  const missing = ANCHORS.filter(n => !QT.includes(n))
  check('六条通话中复核用例都在（少一条，对应方向的变异就能悄悄复活）',
    missing.length === 0, missing.join(','))
  const qtTests = (QT.match(/^\s+@Test/gm) ?? []).length
  check('QuotaServiceTest 用例数 ≥ 26（v2.57 起 20 例 + 本批 6 例）', qtTests >= 26, `解析到 ${qtTests}`)
  check('处理器用例锁住"带ID复核 + 不烧消息配额 + 播报并挂断"',
    V_TEST.includes('asrRound_terminatedWhenDailyCallSecExhaustedMidCall')
      && V_TEST.includes('checkOngoingCallSec("u1", "c1")'))

  // 本批选的是方案 A（回合边界复核）：PSTN 外呼没有回合、静音到底的通话没有新轮次，两者都归候选 ㊻
  const wallClock = [`${SRC}/service/OutboundCallService.java`, `${SRC}/controller/PstnCallbackController.java`,
    `${SRC}/controller/OpenApiCallController.java`].filter(f => read(f).includes('checkOngoingCallSec'))
  check('PSTN 外呼链路不接回合复核（要做墙钟上限得先立口径，不许顺手加）',
    wallClock.length === 0, wallClock.join(','))
  check('语音处理器没有为时长新增定时器（方案 A 明确只在回合边界判）',
    !/ScheduledExecutorService|new Timer|scheduleAtFixedRate/.test(VOICE))
}

console.log('\n[11] 助手模型的三态：没带＝不改、空串＝清空、值＝换成它（v2.60 · C-127 · 候选 ㊸）')
{
  const SRC = 'backend/src/main/java/com/leyon/backend'
  const POLICY_FILE = `${SRC}/service/AssistantPolicy.java`
  const POLICY = read(POLICY_FILE)
  const ROBOT = read('frontend/src/views/SmartRobot.vue')
  const SMOKE = read('scripts/smoke.sh')
  const TEST_DIR = 'backend/src/test/java/com/leyon/backend'

  // 为什么值得立门禁：MyBatis-Plus 的 updateById 跳过 null 列，所以"清空"必须有第三种值；
  // 而这一态一旦在链路任何一处被折成 null（后端归一、前端 undefined），失效形态是"点了没反应"而不是报错。
  const defs = javaFiles('backend/src/main/java')
    .filter(f => /private String clampModelForStorage\(/.test(read(f)))
  check('三态判据恰一处定义（两处＝写侧会给出两种"清空"）',
    defs.length === 1 && defs[0] === POLICY_FILE, defs.join(','))
  const setters = javaFiles('backend/src/main/java').filter(f => /\.setModelName\(/.test(read(f)))
  check('落库侧只有 AssistantPolicy 写 modelName 这一列（别处写就等于绕过三态）',
    setters.length === 1 && setters[0] === POLICY_FILE, setters.join(','))

  const mStart = POLICY.indexOf('private String clampModelForStorage(')
  // 必须按方法体取界：扫到文件尾会让别处的 `return null;` 冒充"缺字段那一态还在"（M4 变异当场抓到的形状）
  const body = POLICY.slice(mStart, POLICY.indexOf('\n    }', mStart))
  check('三态各有出口：null 保留（不动列）、空白归一为 ""（显式清空）、其余用钳制值',
    mStart >= 0 && body.includes('return null;') && body.includes('return "";') && body.includes('return clamped;'))
  check('落库侧仍复用 runtime() 的钳制值，不另算一份模型合法性',
    POLICY.includes('clampModelForStorage(assistant.getModelName(), rt.model())'))
  const clampBody = POLICY.slice(POLICY.indexOf('public void clampForStorage'),
    POLICY.indexOf('private String clampModelForStorage'))
  check('clampForStorage 里 temperature/maxTokens/personality 三列仍直取钳制值（本批改的只有 model）',
    clampBody.includes('assistant.setTemperature(rt.temperature());')
      && clampBody.includes('assistant.setMaxTokens(rt.maxTokens());')
      && clampBody.includes('assistant.setPersonality(rt.personality());'))

  const PT = read(`${TEST_DIR}/service/AssistantPolicyTest.java`)
  const ST = read(`${TEST_DIR}/service/AssistantServiceTest.java`)
  const CT = read(`${TEST_DIR}/controller/AssistantControllerTest.java`)
  const ANCHORS = [
    ['keepsExplicitEmptyModelAsEmptySoPartialUpdateCanClearIt', PT],
    ['normalizesWhitespaceOnlyModelToTheSameExplicitClearToken', PT],
    ['update_forwardsExplicitEmptyModelToTheMapper', ST],
    ['updateAcceptsExplicitEmptyModelAndHandsItThroughToTheWrite', CT]]
  const missing = ANCHORS.filter(([n, src]) => !src.includes(n)).map(([n]) => n)
  check('四条正向用例都在（少一条，对应方向的回归就能悄悄复活）',
    missing.length === 0, missing.join(','))
  // 反向锚点：三态的另一半是"没带这一列不许动它"，被改成"永远写空串"时清空会顺带毁掉配置
  check('两条"缺字段仍保持 null"的反向用例仍在（policy 层 + service 层各一条）',
    PT.includes('keepsUnsetFieldsUnsetSoPartialUpdateStillSkipsThem')
      && ST.includes('update_keepsUnsetModelFieldsNullSoPartialUpdateStillSkipsThem'))
  check('读侧锚点锁住 ""（库里存的就是空串，读出来必须仍走服务端默认）',
    PT.includes('policy.runtime(assistant("", null, null, null)).model()).isNull()'))

  // 前端两个提交点是这条链路的起点：折成 undefined 就等于"没带这一列"，后端再怎么改也收不到清空
  check('两处提交都不再把 modelName 折成 undefined',
    !/modelName:[^\n]*\|\|\s*undefined/.test(ROBOT), (ROBOT.match(/modelName:[^\n]*\|\| undefined/g) ?? []).join(' | '))
  check('保存模型参数那一处确实把空串发出去（settingsModelName 直填）',
    ROBOT.includes('modelName: settingsModelName.value,'))
  const putBlocks = ROBOT.split('updateAssistant({').slice(1).map(s => s.slice(0, s.indexOf('})')))
  const withModel = putBlocks.filter(b => b.includes('modelName:'))
  check('五处 PUT 里只有"保存模型参数"带 modelName（其余四处带上＝保存音色/工具顺手清空模型）',
    putBlocks.length === 5 && withModel.length === 1, `${putBlocks.length} 处 / 带列 ${withModel.length} 处`)
  const createBlock = ROBOT.slice(ROBOT.indexOf('await createAssistant({'))
  check('创建侧整份表单直发（不再逐字段挑）', createBlock.slice(0, 120).includes('...formData.value,'))

  const region36 = SMOKE.slice(SMOKE.indexOf("section '3.6"), SMOKE.indexOf("section '4. 会话与消息'"))
  check('§3.6 区段在位（区段号是冒烟报数口径的一部分）',
    SMOKE.includes("section '3.6 助手模型清空（空串＝显式清空）'"))
  check('§3.6 的正反两条真机锚点都在：空串能清空、只改名不清空',
    region36.includes('清空生效：模型已不是 deepseek-chat')
      && region36.includes('只改名的 PUT 未清空库内模型'))
  check('§3.6 分辨 "" 与 null（折成一态就退回 ㊸，光看 jget 的空串读数看不出来）',
    region36.includes('"modelName":""'))
  // 计的是"请求次数"而不是带引号的变量名：三次 PUT（清空/换回/只改名）+ 两次按 ID 读回
  const reuse = (region36.match(/\$ASSISTANT_ID/g) ?? []).length
  check('§3.6 全部围绕同一个已建助手读写（不新建行、不占助手配额，重复跑结论才稳定）',
    !region36.includes('POST /api/assistants') && reuse >= 5,
    `引用 ${reuse} 次`)
}

console.log('\n[12] 模型出站地址的合成口径 + 流式失败的可见出口（v2.62 · C-128/C-129 · 候选 ㊼ ㊽）')
{
  const SRC = 'backend/src/main/java/com/leyon/backend'
  const GUARD_FILE = `${SRC}/config/ModelBaseUrlGuard.java`
  const GUARD = read(GUARD_FILE)
  const WS = read(`${SRC}/handler/ChatWebSocketHandler.java`)
  const GT = read('backend/src/test/java/com/leyon/backend/config/ModelBaseUrlGuardTest.java')
  const WT = read('backend/src/test/java/com/leyon/backend/handler/ChatWebSocketHandlerTest.java')
  const YAML = read('backend/src/main/resources/application.yaml')
  const ENVX = read('.env.example')
  const README = read('README.md')
  const MANUAL = read('docs/云谕助手项目手册.md')

  // ---- ㊼：base-url 只能填主机根，四处口径必须同源 ----
  // 上游默认出站路径自带 /v1（本地 jar 的 spring-configuration-metadata.json 实测），
  // 所以任何一处仍写 .../v1 的示例值，照它配出来的实例就是"每次对话都失败且没有崩溃迹象"。
  const yamlDefault = (YAML.match(/base-url:\s*\$\{OPENAI_BASE_URL:([^}]*)\}/) ?? [])[1]
  check('yaml 里 base-url 默认值解析有效（正则失效时本组会静默全绿）', yamlDefault !== undefined, String(yamlDefault))
  const envExample = (ENVX.match(/^OPENAI_BASE_URL=(.*)$/m) ?? [])[1]
  check('.env.example 的 OPENAI_BASE_URL 行解析有效', envExample !== undefined, String(envExample))
  const manualRow = (MANUAL.match(/^\| `OPENAI_BASE_URL` \|[^\n]*/m) ?? [])[0]
  const readmeRow = (README.match(/^\| `OPENAI_BASE_URL`[^\n]*/m) ?? [])[0]
  check('手册 5.3 与 README 的口径行都解析到（只改代码不改文档＝下一个人照文档再配错一次）',
    manualRow !== undefined && readmeRow !== undefined, `${!!manualRow}/${!!readmeRow}`)
  const v1Sites = [['yaml 默认值', yamlDefault], ['.env.example', envExample], ['手册 5.3', manualRow], ['README', readmeRow]]
    .filter(([, v]) => /(?:openai\.com|deepseek\.com)\/v1/.test(v ?? ''))
    .map(([n]) => n)
  check('四处口径都不把 /v1 写进 base-url（少一处就留下一份会合成 /v1/v1 的示例）',
    v1Sites.length === 0, v1Sites.join('、'))
  const docSites = [['手册 5.3', manualRow], ['README', readmeRow]]
    .filter(([, v]) => !/主机根/.test(v ?? ''))
    .map(([n]) => n)
  check('两处文档都写明"只填主机根"（口径要能被读到，不是只藏在代码注释里）',
    docSites.length === 0, docSites.join('、'))

  // 判据单点：合成规则若有第二份实现，两处会各自漂移，而"哪个对"要看上游版本
  const judges = javaFiles('backend/src/main/java')
    .filter(f => /static String check\(String baseUrl, String completionsPath\)/.test(read(f)))
  check('出站地址合成判据恰一处定义（两处＝同一个误配有两种结论）',
    judges.length === 1 && judges[0] === GUARD_FILE, judges.join(','))
  check('上游默认路径常量只有一份（别处复制＝上游改默认值时只有一处会跟着变）',
    (javaFiles('backend/src/main/java').filter(f => /\/v1\/chat\/completions/.test(read(f))).length) === 1
    && javaFiles('backend/src/main/java').filter(f => /\/v1\/chat\/completions/.test(read(f)))[0] === GUARD_FILE,
    javaFiles('backend/src/main/java').filter(f => /\/v1\/chat\/completions/.test(read(f))).join(','))

  const vStart = GUARD.indexOf('public void validate()')
  const vBody = GUARD.slice(vStart, GUARD.indexOf('\n    }', vStart))
  check('validate() 解析到方法体（按方法体取界，扫到文件尾会让别处的字面量冒充）', vStart >= 0 && vBody.length > 40)
  check('误配走告警而非阻断（配置笔误不该放大成整站起不来）',
    vBody.includes('logger.warn(warning)') && !vBody.includes('throw '))
  check('启动即打印合成后的出站地址（公网实例没有真实 Key 时，这是唯一的可核对读数）',
    vBody.includes('模型出站地址:'))
  check('守卫已接线为启动期 Bean（@Component + @PostConstruct）',
    GUARD.includes('@Component') && GUARD.includes('@PostConstruct'))

  const GT_ANCHORS = ['v1SuffixIsFlagged', 'hostRootIsAccepted', 'gatewayPathPrefixIsAccepted',
    'shortenedCompletionsPathIsAccepted', 'validateWarnsInsteadOfThrowing']
  const gtMissing = GT_ANCHORS.filter(n => !GT.includes(n))
  check('五条判据用例都在（缺一条＝对应方向的回归能悄悄复活）', gtMissing.length === 0, gtMissing.join(','))
  // 反向锚点：把合规值也判成误配的"更严格实现"同样不可信
  check('反向锚点在位：主机根与网关前缀都必须判为合规',
    GT.includes('assertNull(ModelBaseUrlGuard.check("https://platform.deepseek.com", null))')
      && GT.includes('assertNull(ModelBaseUrlGuard.check("https://gw.example.com/proxy/ai", null))'))

  // ---- ㊽：流式失败的可见出口 ----
  const legStart = WS.indexOf('error -> {')
  const leg = legStart < 0 ? '' : WS.slice(legStart, WS.indexOf('\n                        },', legStart))
  check('流式 error 回调解析到方法体（按回调体取界，扫到文件尾会命中别的 MSG_TYPE_ERROR）',
    legStart >= 0 && leg.length > 40, `取到 ${leg.length} 字符`)
  const eIdx = leg.indexOf('MSG_TYPE_ERROR')
  const aIdx = leg.indexOf('MSG_TYPE_ASSISTANT_MSG')
  const qIdx = leg.indexOf('MSG_TYPE_QUERY_END')
  check('失败出口三帧齐全且顺序为 error → 收尾标记 → query_end（收尾帧必须最后，前端靠它解除打字态）',
    eIdx >= 0 && eIdx < aIdx && aIdx < qIdx, `error=${eIdx} assistant=${aIdx} queryEnd=${qIdx}`)
  check('异常详情仍进服务端日志（帧给可见性、日志给排障）', leg.includes('logger.error('))
  check('反向锚点：error 帧不回显上游异常原文（异常消息可能含 Key、内网地址、供应商名）',
    !/error\.getMessage\(\)|\.getLocalizedMessage\(\)/.test(leg))
  const WT_ANCHORS = ['chat_error_surfacesErrorFrameBeforeClosingFrames',
    'chat_error_errorFrameDoesNotEchoUpstreamDetail']
  const wtMissing = WT_ANCHORS.filter(n => !WT.includes(n))
  check('两条 WS 用例都在（少一条＝"只补收尾帧"的旧形态可以改回去）', wtMissing.length === 0, wtMissing.join(','))
  check('WS 用例锁住顺序与"最后一帧仍是 query_end"',
    WT.includes('types.indexOf("query_end")') && WT.includes('types.get(types.size() - 1)'))
}

console.log('\n[13] 元判据：门禁的红必须落到退出码与具名读数（v2.70 · 批次 E · C-137 的防复活侧）')
{
  // C-137 的教训不是某个产品缺陷，而是**守卫自己失效**：打印"失败 N 项"却 `exit 0`，CI 收下不判。
  // 第 5 组核对"脚本 ↔ 台账 ↔ package.json ↔ ci.yml ↔ AGENTS"的接线，但不核对每道脚本有没有退出码
  // ⇒ 今后新增第十道 Node 桩测时忘了 `process.exit`，会静默复制同一层免疫。本组把那一行本身锁成判据。
  const mjsScripts = readdirSync(join(ROOT, 'scripts')).filter(f => /^check-.*\.mjs$/.test(f)).sort()
  check('本组解析到 ≥9 道 check-*.mjs（glob 失效时逐条判据全体空转）', mjsScripts.length >= 9, `解析到 ${mjsScripts.length}`)
  for (const f of mjsScripts) {
    // 逐字锁规范行（三元式与变量名一起）：`process.exit(0)`、缺行、或自创写法都算"打印红、不判决"
    check(`${f} 以 process.exit(failures === 0 ? 0 : 1) 收尾`,
      /process\.exit\(failures === 0 \? 0 : 1\)/.test(read(`scripts/${f}`)))
  }

  // smoke.sh 的退出码语义与 §7.2 的两处结构性边界——写成注释的边界不算判据，落成断言才算。
  const SM = read('scripts/smoke.sh')
  const failGate = SM.indexOf('[ "$FAIL" -eq 0 ] || exit 1')
  check('smoke.sh 汇总区存在"有 FAIL 即 exit 1"的门（SKIP 允许、红不许吞）', failGate >= 0)
  check('FAIL 门排在末尾裸 exit 0 之前（顺序颠倒＝无论结果都绿，v2.69 的 M2 态取证依赖它）',
    failGate >= 0 && SM.indexOf('exit 0', failGate) > failGate)
  const liveIdx = SM.indexOf('if [ "${SMOKE_WS_CHAT:-0}" = 1 ]; then')
  const liveBranch = liveIdx < 0 ? '' : SM.slice(liveIdx, SM.indexOf('if [ "$KEEP" = 1 ]', liveIdx))
  check('真实回合段由 SMOKE_WS_CHAT 显式放行守卫包住（默认关：冒烟不碰消耗外部额度的接口）', liveIdx >= 0)
  check('未放行时落具名 skip 而不是静默消失（少一条断言必须能从读数里看见）',
    liveBranch.includes(`skip '真实回合的终局帧' 'SMOKE_WS_CHAT`))
  const nodeGuard = SM.indexOf('if [ "$WS_FRAME_NODE" != 1 ]; then')
  check('缺 Node ≥22 全局 WebSocket 时整节 §7.2 落具名 SKIP（前提不满足 ≠ 通过）',
    nodeGuard >= 0 && SM.slice(nodeGuard).includes(`skip '聊天 WS 帧级判据（整节）'`))
}

console.log('\n[14] 模型流式调用：超时与取消都只有单点（v2.71 · C-139/C-140，判据来自手册 4.5 第 28 条）')
{
  // 一次模型调用要能在"上游不再吐分块"和"用户已经走了"两种情况下都收场。
  // 这两件事各只有一处判据（适配器出口 / 对话循环的内层订阅句柄），分成两份时的失效形态是
  // "文本通道有超时、语音与开放 SSE 没有"，而没人会去数通道。
  const ADAPTER_FILE = 'backend/src/main/java/com/leyon/backend/service/OpenAiModelAdapter.java'
  const CHAT_FILE = 'backend/src/main/java/com/leyon/backend/service/ChatService.java'
  const ADAPTER = read(ADAPTER_FILE)
  const CHAT = read(CHAT_FILE)
  const YAML = read('backend/src/main/resources/application.yaml')
  const ENVX = read('.env.example')
  const META = read('backend/src/main/resources/META-INF/additional-spring-configuration-metadata.json')
  const README = read('README.md')
  const MANUAL = read('docs/云谕助手项目手册.md')
  const AT = read('backend/src/test/java/com/leyon/backend/service/OpenAiModelAdapterTest.java')
  const CT = read('backend/src/test/java/com/leyon/backend/service/ChatServiceTest.java')

  // ---- 超时单点 ----
  const timeoutSites = javaFiles('backend/src/main/java').filter(f => read(f).includes('.timeout('))
  check('全仓只有模型适配器出口施加超时（第二处＝同一次调用有两个结论，且没人知道哪个先触发）',
    timeoutSites.length === 1 && timeoutSites[0] === ADAPTER_FILE, timeoutSites.join(','))
  check('ChatService 不自己计时（写进对话循环＝只有它服务的通道被保护，且每轮工具续答要各写一遍）',
    !CHAT.includes('.timeout('))
  check('超时挂在 stream() 的出口上（挂在构造期或客户端层就量不到"分块之间的静默"）',
    ADAPTER.includes('return chatModel.stream(prompt).timeout(streamTimeout);'))
  check('超时值由配置注入（写死＝公网实例遇到慢供应商只能改码重发）',
    ADAPTER.includes('@Value("${app.ai.stream-timeout-ms}")'))

  const yamlTimeout = (YAML.match(/stream-timeout-ms:\s*\$\{AI_STREAM_TIMEOUT_MS:([^}]*)\}/) ?? [])[1]
  check('yaml 的 stream-timeout-ms 默认值解析有效（正则失效时本组会静默全绿）',
    yamlTimeout !== undefined, String(yamlTimeout))
  const envTimeout = (ENVX.match(/^AI_STREAM_TIMEOUT_MS=(.*)$/m) ?? [])[1]
  check('.env.example 有 AI_STREAM_TIMEOUT_MS 行', envTimeout !== undefined)
  check('yaml 默认值与模板取值相等（两处不同＝照模板配出来的实例和不配模板的实例行为不一样）',
    yamlTimeout === envTimeout, `yaml ${yamlTimeout} / env ${envTimeout}`)
  check('IDE 元数据登记了 app.ai.stream-timeout-ms（配得到却没人知道＝文档与补全都缺）',
    META.includes('"name": "app.ai.stream-timeout-ms"'))
  const manualRow = (MANUAL.match(/^\| `AI_STREAM_TIMEOUT_MS`[^\n]*/m) ?? [])[0]
  const readmeRow = (README.match(/^\| `AI_STREAM_TIMEOUT_MS`[^\n]*/m) ?? [])[0]
  check('手册 5.3 与 README 配置表都有这一行（只改代码＝下一个人照文档配不出这个行为）',
    manualRow !== undefined && readmeRow !== undefined, `${!!manualRow}/${!!readmeRow}`)
  const docSites = [['手册 5.3', manualRow], ['README', readmeRow]]
    .filter(([, v]) => !/静默/.test(v ?? ''))
    .map(([n]) => n)
  check('两处文档都写明"静默超时"（写成"总时长上限"会引导运维把慢回答当成故障去调大它）',
    docSites.length === 0, docSites.join('、'))

  // ---- 取消单点 ----
  const streamSites = javaFiles('backend/src/main/java').filter(f => read(f).includes('modelAdapter.stream('))
  check('取模型流的调用点全仓恰一处（绕过它＝超时与取消两道判据同时落空）',
    streamSites.length === 1 && streamSites[0] === CHAT_FILE, streamSites.join(','))
  check('内层订阅都被登记进同一个句柄（丢弃 Disposable＝handler 里那句"停止流式订阅"只是注释）',
    CHAT.includes('trackInFlight.accept(modelAdapter.stream(prompt).subscribe(')
      && CHAT.includes('trackInFlight.accept(handleToolCalls('))
  check('取消回调两件事齐全：撤在途订阅 + 把已生成的部分落库',
    CHAT.includes('sink.onCancel(() -> {')
      && CHAT.includes('Disposable inFlight = innerSubscription.getAndSet(null);')
      && CHAT.includes('saveTurnOnce.run();'))
  check('登记之后复核取消位（只放进槽或只复核，都留得下"取消没看到、登记也没看到"的漏网订阅）',
    CHAT.includes('innerSubscription.set(subscription);') && CHAT.includes('if (sink.isCancelled()) {'))
  check('一轮记录只允许 onComplete 或 onCancel 一方落库（CAS 守卫，竞态时不双份）',
    (CHAT.match(/turnSaved\.compareAndSet\(false, true\)/g) ?? []).length === 1)
  check('累计正文是 StringBuffer（取消线程要读它，无锁读 StringBuilder 可能读到撕裂内容）',
    CHAT.includes('StringBuffer fullResponse = new StringBuffer();'))
  const assembly = javaFiles('backend/src/main/java').filter(f => read(f).includes('new ChatService('))
    .map(f => basename(f)).sort()
  check('三条对话通道的装配点都还在（少一条＝本组对某条通道不再成立）',
    assembly.length === 3, assembly.join(','))

  // ---- 判据用例：正向 + 反向 ----
  const AT_ANCHORS = ['stream_silenceTimeoutAbortsHungUpstreamAndCancelsIt',
    'stream_progressingStreamCompletesWithinSameWindow']
  const atMissing = AT_ANCHORS.filter(n => !AT.includes(n))
  check('适配器两条用例都在（缺一条＝对应方向的退化能悄悄复活）', atMissing.length === 0, atMissing.join(','))
  check('超时用例同时锁"下游收到 TimeoutException"与"Reactor 上游订阅被撤"',
    AT.includes('expectError(TimeoutException.class)') && AT.includes('assertThat(upstreamCancelled).isTrue()'))
  check('反向锚点在位：同一窗口内慢而持续推进的流必须完整跑完（写成总时长上限的实现在此红）',
    AT.includes('expectNextCount(5)') && AT.includes('verifyComplete()'))
  check('取消用例锁两个读数：上游被撤 + 中断轮次的部分正文落库',
    CT.includes('cancel_propagatesToUpstreamModelSubscriptionAndSavesPartialTurn')
      && CT.includes('assertThat(upstreamCancelled).isTrue()') && CT.includes('前半后半'))
  check('反向锚点在位：正常收尾之后的 dispose 不得再落一次库',
    CT.includes('disposeAfterNormalCompletionDoesNotSaveTheTurnAgain'))

  // ---- 判据管到哪一层，边界就必须登记到哪一层（真机读数：手册 7.5 v2.71 行 / 7.4 S-26）----
  // 取消传播到的是 Reactor 订阅；到模型服务的在途 HTTP 交换并不因此中止（本机 WebClient 落到
  // JdkClientHttpConnector，而 JDK HttpClient 的交换不因 body subscription 取消而拆断）。
  // 这一条若只写在 7.4 而两处导读层各写一次"撤掉了模型调用"，运维就会以为用户走了钱就停了。
  const leakSites = [['README', README], ['手册', MANUAL]].filter(([, t]) => !/S-26/.test(t)).map(([n]) => n)
  check('两处文档都指向 S-26（"撤订阅"写成"撤调用"＝宣称比证据走得远）',
    leakSites.length === 0, leakSites.join('、'))
  check('适配器注释不出现"任何一次模型调用都不会无限挂住"（回合收场不等于交换中止）',
    !ADAPTER.includes('任何一次模型调用都不会无限挂住'))
  const lagSites = [['手册', MANUAL]].filter(([, t]) => !/buffer\(2, 1\)|滞后一拍/.test(t)).map(([n]) => n)
  check('文档写明 Spring AI 的 buffer(2, 1) 让分帧滞后一拍（不写＝"中断轮次少最后一块"和"上游 150ms 前端 2ms"两处读数无法解释）',
    lagSites.length === 0, lagSites.join('、'))
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
