/**
 * 页面布局收口验证：左列表右内容归属明确、整屏根/导航/登出各只一处、
 * 弹层遮罩自己就是滚动容器。这类形状回归是静默的——不会有单测变红，
 * 只会在下次有人复制遮罩时再扩散一份，所以用静态源码判据锁死。
 * 运行：node scripts/frontend/check-page-layout.mjs
 */

import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '../..')

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
 * 显式读入 + 缺文件即具名 FAIL。
 * 不用"读不到就跳过"：v2.53 踩过——文件驱动型断言会在被检查树缺文件时静默消失，
 * 于是"整组绿"可能只是"整组没跑"。
 */
const missing = new Set()
function src(rel) {
  const abs = join(ROOT, rel)
  if (!existsSync(abs)) {
    if (!missing.has(rel)) {
      missing.add(rel)
      failures++
      console.log(`  FAIL 依赖的文件不存在：${rel} — 缺文件不等于判据通过`)
    }
    return ''
  }
  return readFileSync(abs, 'utf8')
}

const SHELL = 'frontend/src/components/PageShell.vue'
const NAV = 'frontend/src/components/NavList.vue'
const VIEWS = {
  robot: 'frontend/src/views/SmartRobot.vue',
  records: 'frontend/src/views/CallRecords.vue',
  billing: 'frontend/src/views/Billing.vue',
  org: 'frontend/src/views/Org.vue',
  apps: 'frontend/src/views/Apps.vue',
  admin: 'frontend/src/views/Admin.vue',
}
const MANAGED = [VIEWS.records, VIEWS.billing, VIEWS.org, VIEWS.apps, VIEWS.admin]

const pageShell = src(SHELL)
const navList = src(NAV)
const robot = src(VIEWS.robot)
const records = src(VIEWS.records)
const billing = src(VIEWS.billing)
const org = src(VIEWS.org)
const apps = src(VIEWS.apps)
const admin = src(VIEWS.admin)

console.log('\n[1] 左列表 + 右内容的壳只有一处')
{
  check('PageShell 的根是"占满视口、自己不滚"的分栏容器',
    /h-screen/.test(pageShell) && /w-full/.test(pageShell) && /overflow-hidden/.test(pageShell)
    && /flex/.test(pageShell))
  check('PageShell 左栏是语义 aside、固定窄栏（w-72，与对话框同宽）',
    /<aside[^>]*w-72/.test(pageShell) && /flex-shrink-0/.test(pageShell))
  check('PageShell 右栏是语义 main、可收缩（min-w-0 防长内容撑破左栏）',
    /<main[^>]*flex-1/.test(pageShell) && /min-w-0/.test(pageShell))
  check('左栏列表区是具名槽（各页自己的列表从这里进来，壳不认具体业务）',
    /<slot\s+name="list"/.test(pageShell))
  check('壳内含品牌头（全站唯一一份 "WORKSPACE //" 副标题）',
    pageShell.includes('WORKSPACE //') && !robot.includes('WORKSPACE //')
    && !records.includes('WORKSPACE //') && !admin.includes('WORKSPACE //'))
  check('壳内含用户区与登出（全站唯一一份，视图不再各自实现）',
    /clearSession\(/.test(pageShell) && /logout\(/.test(pageShell) && /登出/.test(pageShell))
  const withLogoutHandler = Object.entries(VIEWS)
    .filter(([, p]) => src(p).includes('handleLogout'))
    .map(([k]) => k)
  check('六视图不再自己写 handleLogout（登出链路也归壳，不留第二份）',
    withLogoutHandler.length === 0, withLogoutHandler.join(','))
  for (const rel of [...MANAGED, VIEWS.robot]) {
    check(`${rel.split('/').pop()} 走 PageShell 而非自声明整屏根`,
      src(rel).includes('<PageShell') && !/min-h-screen flex flex-col/.test(src(rel))
      && !/h-screen w-full flex flex-col overflow-hidden/.test(src(rel)))
  }
  const screenRoots = (robot.match(/h-screen[^"]*overflow-hidden/g) ?? []).length
  check('SmartRobot 只有骨架屏分支自声明整屏根（loaded 分支交给壳，弹窗上的 overflow-hidden 不算）',
    screenRoots === 1, `读到 ${screenRoots} 处整屏根`)
  check('通话清理不因登出搬家而减弱（ws / voiceWs / webrtc 仍在卸载时收口）',
    /onBeforeUnmount\(\(\) => \{[\s\S]*?ws\?\.close\(\)[\s\S]*?voiceWs\?\.close\(\)[\s\S]*?webrtc\.hangup\(\)/.test(robot))
}

console.log('\n[2] 导航是单一实现，且当前项可读出来')
{
  check('NavList 存在且逐项点名六条路径',
    ['smartrobot', 'records', 'billing', 'org', 'apps', 'admin']
      .every(p => navList.includes(`/${p}`)))
  check('管理后台项按角色出现（不是靠 CSS 藏起来）',
    /adminOnly/.test(navList) && navList.includes("localStorage.getItem('role')"))
  check('当前项高亮有可机读的判据（useRoute + 路径相等 + aria-current）',
    /useRoute\(\)/.test(navList) && /route\.path === /.test(navList) && /aria-current/.test(navList))
  check('导航项是真 button（键盘能走完；DESIGN.md 的无障碍底线"只增不减"）',
    /<button/.test(navList) && !/<div[^>]*@click/.test(navList))
  const inlineNav = [...MANAGED, VIEWS.robot].flatMap(rel =>
    ['/smartrobot', '/records', '/billing', '/org', '/apps', '/admin']
      .filter(p => src(rel).includes(`router.push('${p}')`))
      .map(p => `${rel.split('/').pop()}→${p}`))
  check('视图里不再内联导航按钮（第二份导航＝第二份会腐烂的清单）',
    inlineNav.length === 0, inlineNav.join(','))
  check('SmartRobot 不再自己判 admin（角色判定归 NavList 一处）',
    !robot.includes(`v-if="userRole === 'admin'"`))
  check('/chatrobot 刻意不入导航（站内无入口的调试页，改它属另一批）',
    !navList.includes('/chatrobot'))
}

console.log('\n[3] 每页的"左列表 / 右内容"归属明确')
{
  check('CallRecords 把记录列表放进左槽', /<template #list>/.test(records))
  check('CallRecords 的详情不再用遮罩弹窗（取消 fixed inset-0 即取消整屏接管）',
    !records.includes('fixed inset-0') && !records.includes('showDetail'))
  check('CallRecords 仍按 id 取详情、仍惰性取录音（只换渲染位置，不改数据链路）',
    records.includes('fetchCallRecordDetail') && records.includes('fetchRecordingBlob')
    && records.includes('revokeObjectURL'))
  check('Apps 把应用列表放进左槽并持选中项', /<template #list>/.test(apps)
    && /selectedApp/.test(apps))
  check('Apps 的创建/凭据/能力/吊销四个流程弹窗保留（它们是动作，不是浏览）',
    (apps.match(/geek-modal-mask/g) ?? []).length === 4,
    `读到 ${(apps.match(/geek-modal-mask/g) ?? []).length} 处遮罩`)
  check('Apps 的三态台账判据仍走统一入口（没有因为改版复制第二份）',
    apps.includes('ledgerCellFor'))
  check('Org 把组织列表放进左槽，成员面板进右栏', /<template #list>/.test(org)
    && org.includes('selectedOrg'))
  check('Org 不再需要"返回列表"（主从同屏，返回按钮失去意义）',
    !org.includes('返回列表') && !org.includes('ChevronLeft'))
  check('Admin 的面板入口收成列表数据（五键逐一点名，不靠 UI 文案）',
    ['audit', 'users', 'archive', 'quotas', 'invites'].every(k => admin.includes(`'${k}'`))
    && /v-for="panel in panels"/.test(admin))
  check('Billing 不硬造一个左列表（它本来没有条目集合，导航即左栏内容）',
    !billing.includes('<template #list>'))
  // 浏览器走查发现的缺口：改版前"选中"只存在于弹窗里，改版后它是左列表的状态，
  // 只有 class 没有语义属性的话，屏幕阅读器与后续门禁都读不到"当前是哪一条"。
  const noAriaCurrent = [
    ['CallRecords', records, /v-for="record in records"/],
    ['Apps', apps, /v-for="app in apps"/],
    ['Org', org, /v-for="org in orgs"/],
    ['Admin', admin, /v-for="panel in panels"/],
  ].filter(([name, text, vfor]) => {
    const at = text.search(vfor)
    if (at < 0) throw new Error(`${name} 找不到列表 v-for 锚点——列表被改名了，判据须同步`)
    return !/aria-current/.test(text.slice(at, at + 700))
  }).map(([name]) => name)
  check('主从列表的选中项带 aria-current（选中态要能被读出来，不只是换个底色）',
    noAriaCurrent.length === 0, noAriaCurrent.join(','))
}

console.log('\n[4] 新组件不引入第二套样式语言、不引新依赖')
{
  const styleCss = src('frontend/src/style.css')
  for (const [name, text] of [[SHELL, pageShell], [NAV, navList]]) {
    const file = name.split('/').pop()
    check(`${file} 不写死颜色（hex / rgba）`,
      !/#[0-9a-fA-F]{3,8}\b/.test(text) && !/rgba\(/.test(text))
    const sources = [...text.matchAll(/from '([^']+)'/g)].map(m => m[1])
    check(`${file} 只依赖 vue / vue-router / lucide 与仓内既有出口（禁止清单里的新依赖）`,
      sources.every(s => ['vue', 'vue-router', 'lucide-vue-next'].includes(s)
        || s.startsWith('./') || s.startsWith('../api/') || s.startsWith('../composables/')),
      sources.join(','))
    check(`${file} 用 .geek-* 全局类而不是自造一套控件`,
      /\bgeek-(btn|card|input|scroll|surface|badge|divider|listrow)\b/.test(text))
    check(`${file} 不定义自己的 <style>（样式单点在 style.css，否则"第三次抽取"又被绕开）`,
      !/<style/.test(text))
  }
  check('左栏列表行的形状在 style.css 里定义一次（AGENTS：共享控件是 style.css 的 .geek-* 全局类）',
    (styleCss.match(/\.geek-listrow\b[^-{]*\{/g) ?? []).length >= 1
    && styleCss.includes('.geek-listrow--active'))
  // 浏览器走查发现的缺口：对话框页（用户指定的参照）的选中态是左侧 accent 描边，
  // 改版后的五个管理页只有"底色 + 加粗"。同一个"当前项"在两处读起来不一样，
  // 而布局壳统一正是为了让六个页面共用一套左列表语言。
  const activeBlock = styleCss.match(/\.geek-listrow--active\s*\{([^}]*)\}/)?.[1] ?? ''
  check('选中态的 affordance 与对话框页一致（accent 左边框，定义在共享类里）',
    /border-left:\s*3px solid var\(--geek-accent\)/.test(activeBlock), activeBlock.trim().slice(0, 80))
  check('对话框页的选中描边用同一个 accent 变量（两处定义只允许在颜色上同源）',
    /\.assistant-item\.item-active\s*\{[^}]*border-left:\s*3px solid var\(--geek-accent\)/.test(robot))
  check('描边不挤动文字（选中态同时补偿 padding-left）',
    /padding-left:\s*calc\(12px - 3px\)/.test(activeBlock), activeBlock.trim().slice(0, 80))
  check('暗色口径不只在亮色成立（走 var(--geek-*) 而不是常量）',
    /var\(--geek-/.test(pageShell) || /bg-geek-/.test(pageShell))
}

console.log('\n[5] 反向锚点：判据读的是代码实况，不是文档或自身')
{
  check('PageShell 确实落在 frontend/src/components 下（不是在 .scratch 副本里自证）',
    existsSync(join(ROOT, SHELL)))
  check('六个受控视图全部参与判定（漏一个＝改版只做了一半）',
    [...MANAGED, VIEWS.robot].every(rel => src(rel).length > 0))
  check('六视图都 import 了壳（用了组件名却没 import＝模板是死的）',
    [...MANAGED, VIEWS.robot].every(rel => src(rel).includes("import PageShell from '../components/PageShell.vue'")))
}

console.log('\n[6] 弹层：遮罩是滚动容器，居中交给卡片外边距（候选 ㊾ 的收口形状）')
{
  const styleCss = src('frontend/src/style.css')
  const maskBlock = styleCss.match(/\.geek-modal-mask\s*\{([^}]*)\}/)?.[1] ?? ''
  const cardBlock = styleCss.match(/\.geek-modal-card\s*\{([^}]*)\}/)?.[1] ?? ''
  check('style.css 把遮罩定义为"贴住视口 + 竖向可滚"的 flex 容器',
    /position:\s*fixed/.test(maskBlock) && /inset:\s*0/.test(maskBlock)
    && /display:\s*flex/.test(maskBlock) && /overflow-y:\s*auto/.test(maskBlock),
    maskBlock.trim().slice(0, 120) || '未定义 .geek-modal-mask')
  check('卡片用 auto 外边距居中（空间够时居中、不够时贴顶且滚得出来）',
    /margin:\s*auto/.test(cardBlock), cardBlock.trim().slice(0, 60) || '未定义 .geek-modal-card')
  // 逐文件点名，不取"扫到的第一条"：某页把遮罩改回内联形状时必须是那一页红
  const MASK_SITES = [
    [VIEWS.robot, 5], [VIEWS.org, 2], ['frontend/src/views/ChatRobot.vue', 2], [VIEWS.apps, 4],
  ]
  for (const [rel, expected] of MASK_SITES) {
    const text = src(rel)
    const file = rel.split('/').pop()
    const masks = (text.match(/geek-modal-mask/g) ?? []).length
    check(`${file} 的 ${expected} 处遮罩都走共享类`, masks === expected, `读到 ${masks} 处`)
    check(`${file} 不再内联遮罩定位（fixed inset-0 只允许出现在 style.css 那一处定义里）`,
      !text.includes('fixed inset-0'))
    const cards = (text.match(/geek-modal-card/g) ?? []).length
    check(`${file} 遮罩与居中卡片一一对应（数量相等，不写 >=：0 张遮罩也能让 >= 恒绿）`,
      cards === masks, `遮罩 ${masks} / 卡片 ${cards}`)
  }
  const unsafe = MASK_SITES.map(([rel]) => src(rel)).filter(t =>
    /geek-modal-mask[^"]*items-center/.test(t) || /items-center justify-center[^"]*geek-modal-mask/.test(t))
  check('遮罩上不再出现 items-center（align-items 居中在溢出时会把卡片上下同时裁掉）',
    unsafe.length === 0, `${unsafe.length} 个文件带病`)
  check('CallRecords 改版后没有遮罩（详情是右栏内联，不是弹窗）',
    !records.includes('geek-modal-mask') && !records.includes('fixed inset-0'))
  check('Admin 的弹层不设第二套遮罩形状（它只有确认类弹窗，走同一个类）',
    !admin.includes('fixed inset-0'))
  // v2.63 遗留的核对项：右栏由 h-screen overflow-hidden 的壳包住，内容自己必须会滚
  for (const rel of MANAGED) {
    const file = rel.split('/').pop()
    check(`${file} 的右栏内容带滚动出口（min-h-0 + overflow-y-auto，否则超出视口的部分永久看不到）`,
      /flex-1 min-h-0 overflow-y-auto/.test(src(rel)))
  }
  check('SmartRobot 的右栏是消息区滚动（chat-container 收 min-h-0，输入区不跟着滚）',
    /chat-container flex-1 min-h-0/.test(robot))
}

console.log(`\n共 6 组，FAIL ${failures}`)
process.exit(failures === 0 ? 0 : 1)
