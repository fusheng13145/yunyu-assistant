/**
 * 登记表与代码实况的一致性验证（v2.52 · C-117 · 候选 ㊱ 的门禁侧）。
 *
 * 这里锁的不是"文档写得好不好"，而是**文档里的每一条清单能不能被代码指向**：
 * 十组判据全部是"集合相等"、"逐项对应"或"唯一入口"，所以任何一侧单独漂移都会红——
 * 加了新 Kind 而没登记 ⇒ 红；登记了一个代码里没有的端点 ⇒ 红；
 * 新增 `check-*.mjs` 而没进 CI ⇒ 红；配额上界出现第二份字面量 ⇒ 红。
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

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
