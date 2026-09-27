/**
 * 开放平台拒绝台账的读数分格（v2.50 · C-111）
 *
 * 后端 `GET /api/openapi/denials` 给的是「事件种类 × 应用」的聚合行，其中 `app` 为 null 的行
 * 是"认不出归属"的那一格（无 Key / 错 Key / 握手超限），对所有登录用户可见。两个真实缺陷形态
 * 都由这个纯函数收口，因此它不碰 fetch、可被 `scripts/check-denial-ledger.mjs` 直接注入验证：
 * ①未归属的行不能挂到某个应用名下——那会把全局计数伪装成"你这把 Key 被拒了 N 次"；
 * ②"读不到"必须与"零次被拒"分家——把请求失败渲染成 0，等于把故障报成好消息
 *   （与 v2.49 给通话录音上传收口的是同一类缺陷）。
 */

/** 与后端 OpenApiDenialMeter.Entry 对齐的一行；app 为 null 即全局的未归属格 */
export interface LedgerRow {
  app: string | null
  kind: string
  kindLabel: string
  count: number
}

/** unavailable=读不到或无法判定；quiet=确认零次被拒；denied=有行可展示 */
export type LedgerTone = 'unavailable' | 'quiet' | 'denied'

export interface LedgerCell {
  tone: LedgerTone
  rows: LedgerRow[]
}

/**
 * 某个应用的那一格。`failed` 是"这次读数请求本身没成功"，此时一律 unavailable：
 * 空数组既可能是真的没有拒绝、也可能是 rows 被清空了，两种语义不能合并显示。
 */
export function ledgerCellFor(
  rows: LedgerRow[] | null | undefined,
  appId: string | null | undefined,
  failed: boolean,
): LedgerCell {
  if (failed || !Array.isArray(rows) || !appId) {
    return { tone: 'unavailable', rows: [] }
  }
  const mine = rows.filter(row => row.app === appId)
  return { tone: mine.length > 0 ? 'denied' : 'quiet', rows: mine }
}

/** 全局未归属格（app 为 null/空串/缺字段都算，服务端只承诺 null） */
export function unattributedRows(rows: LedgerRow[] | null | undefined): LedgerRow[] {
  if (!Array.isArray(rows)) {
    return []
  }
  return rows.filter(row => !row.app)
}
