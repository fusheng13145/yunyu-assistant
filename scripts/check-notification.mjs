/**
 * 前端全局通知（useNotification）的可执行验证。
 *
 * 与 check-auth-session.mjs 同一口径：前端没有测试框架（见手册 4.8），此脚本用 Node
 * 自带的 TS 类型剥离直接 import frontend/src/composables/useNotification.ts，
 * 以桩替 setTimeout / clearTimeout 锁住定时器与状态机行为。
 * 运行：node scripts/check-notification.mjs
 */

const MOD = new URL('../frontend/src/composables/useNotification.ts', import.meta.url).href

let failures = 0
function check(name, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${name}`)
  } else {
    failures++
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

// ---------------------------------------------------------------- 定时器桩
// pending：仍可被系统触发的定时器；fired：已取消但保留回调，用于模拟"漏清定时器仍被触发"。
const pending = new Map()
const cancelled = new Map()
let timerSeq = 0
const delays = []

globalThis.setTimeout = (cb, ms) => {
  const id = ++timerSeq
  pending.set(id, cb)
  delays.push(ms)
  return id
}
globalThis.clearTimeout = (id) => {
  if (pending.has(id)) {
    cancelled.set(id, pending.get(id))
    pending.delete(id)
  }
}
/** 触发仍在等待的定时器（按登记顺序，快照后逐个执行，允许回调再排新定时器） */
function firePending() {
  for (const [id, cb] of [...pending.entries()]) {
    pending.delete(id)
    cb()
  }
}

const mod = await import(MOD)
const { notification, show, hide } = mod.useNotification()
const state = () => ({ show: notification.value.show, message: notification.value.message, type: notification.value.type })
const reset = () => {
  hide()
  pending.clear()
  cancelled.clear()
  delays.length = 0
}

// ---------------------------------------------------------------- 单次弹出
console.log('\n[1] show() 会置位状态，类型缺省为 info')
{
  reset()
  show('加载应用列表失败', 'error')
  check('显示中', notification.value.show === true)
  check('文案落到状态', notification.value.message === '加载应用列表失败')
  check('类型落到状态', notification.value.type === 'error')
  check('排了正好一个定时器', pending.size === 1, `实际 ${pending.size}`)
  check('停留时长维持既有 3s 口径', delays[0] === 3000, `实际 ${delays[0]}`)
  reset()
  show('已清空知识库选择')
  check('未传类型时为 info', notification.value.type === 'info')
}

// ---------------------------------------------------------------- 连发不叠加
console.log('\n[2] 连发只保留最后一条，且任何时刻只剩一个待触发定时器')
{
  reset()
  show('第一条', 'info')
  const first = [...pending.keys()][0]
  show('第二条', 'success')
  show('第三条', 'error')
  check('三条都排定时器但只剩一个待触发', pending.size === 1, `实际 ${pending.size}`)
  check('内容取最后一条', notification.value.message === '第三条')
  check('类型取最后一条', notification.value.type === 'error')
  check('前一个定时器被取消', !pending.has(first) && cancelled.has(first))
  firePending()
  check('最后一条自动隐藏后不再被前面的定时器二次触发', state().show === false)
}

// ---------------------------------------------------------------- 自动隐藏
console.log('\n[3] 到点自动隐藏，不靠调用方自己收尾')
{
  reset()
  show('文件删除成功', 'success')
  check('触发前可见', notification.value.show === true)
  firePending()
  check('触发后隐藏', notification.value.show === false)
  check('隐藏后不再有等待中的定时器', pending.size === 0)
  check('隐藏后文案仍在（不打断过渡动画）', notification.value.message === '文件删除成功')
}

// ---------------------------------------------------------------- hide() 的定时器责任
console.log('\n[4] hide() 必须取消待触发定时器，否则旧定时器会关掉下一条消息')
{
  reset()
  show('旧消息', 'info')
  const stale = [...pending.keys()][0]
  hide()
  check('hide 后立即隐藏', notification.value.show === false)
  check('hide 取消了待触发定时器', pending.size === 0, `仍有 ${pending.size}`)
  check('取消动作真的发生了（clearTimeout 被调用）', cancelled.has(stale))
  show('新消息', 'error')
  check('新消息可见', state().show === true && notification.value.message === '新消息')
  check('此刻系统里只有新消息自己那一个定时器，没有旧定时器可误伤', pending.size === 1 && !pending.has(stale))
  firePending()
  check('新消息按自己的时长隐藏', notification.value.show === false)
}

// ---------------------------------------------------------------- 全局单例
console.log('\n[5] 多次 useNotification() 共用同一份状态（App.vue 挂载点与视图调用点是同一个）')
{
  reset()
  const again = mod.useNotification()
  check('暴露的是同一个 ref 实例', again.notification === notification)
  show('由 A 调用点发起', 'warning')
  check('B 调用点立刻看得到', again.notification.value.message === '由 A 调用点发起')
  check('B 调用点的 hide 能关掉 A 发起的消息', (again.hide(), notification.value.show === false))
  reset()
}

// ---------------------------------------------------------------- 纯函数边界
console.log('\n[6] 视图可用性：hide 幂等、show 可在隐藏后重新拉起')
{
  reset()
  hide()
  hide()
  check('重复 hide 不残留定时器也不抛错', pending.size === 0 && notification.value.show === false)
  show('恢复', 'success')
  firePending()
  show('再来一条', 'info')
  check('隐藏后可重新弹出', state().show === true && notification.value.message === '再来一条')
  firePending()
  reset()
}

console.log(failures === 0 ? '\n全部通过（0 失败）' : `\n失败 ${failures} 项`)
process.exit(failures === 0 ? 0 : 1)
