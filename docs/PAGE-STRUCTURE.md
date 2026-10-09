# 页面与组件结构（PAGE-STRUCTURE）

> 逐页实况：入口、区域、数据来源、状态口径、后端形状依赖。所有 `文件:行` 相对于 `frontend/src/`。
> 视觉规范（颜色/间距/控件类名）在 [DESIGN.md](../DESIGN.md)，写法约束在
> [COMPONENT-GUIDELINES.md](COMPONENT-GUIDELINES.md)。

## 1. 路由

| 路径 | 视图 | meta | 说明 |
|---|---|---|---|
| `/` | — | — | `redirect: '/login'`（`router/index.ts:7-10`） |
| `/login` | `Login.vue` | 无 | 已登录访问会被反向送到工作台 |
| `/register` | `Register.vue` | 无 | 同上 |
| `/smartrobot` | `SmartRobot.vue` | `requiresAuth` | **工作台主视图**（非管理员命中 `/admin` 时的落地页）；站点导航不在它内部，而在壳的 `NavList`（见第 1 节末段） |
| `/chatrobot` | `ChatRobot.vue` | `requiresAuth` | 单助手调试页。**站内没有任何入口指向它**（只能手输地址），且必须带 `?assistantId=` |
| `/records` | `CallRecords.vue` | `requiresAuth` | 通话记录与用量聚合 |
| `/org` | `Org.vue` | `requiresAuth` | 组织与成员 |
| `/billing` | `Billing.vue` | `requiresAuth` | 用量配额（只读） |
| `/apps` | `Apps.vue` | `requiresAuth` | 开放平台应用与拒绝台账 |
| `/admin` | `Admin.vue` | `requiresAuth + requiresAdmin` | 管理后台 |
| `/:pathMatch(.*)*` | `NotFound.vue` | 无 | 静态 404；**受保护路径未登录时不会到这里**（守卫先跳登录） |

守卫是 `router/index.ts:71-90` 的单个全局 `beforeEach`，顺序固定：

1. `requiresAuth` 且有令牌但未临期外→ 先 `await renewToken()`（进路由前换发，避免首个请求 401）；
2. 过期令牌按 `null` 处理；
3. `requiresAuth && !token` → `/login`；
4. `requiresAdmin && role !== 'admin'` → `/smartrobot`（`role` 读自 `localStorage`）；
5. 已登录访问 `/login` `/register` → `/smartrobot`。

**导航入口只有一处**：`components/NavList.vue:23-30` 的六项清单（第 6 项"管理后台"按 `role` 过滤），
由 `components/PageShell.vue` 统一挂载。**v2.63 起布局壳是全站唯一形状**：
`PageShell.vue:2` 声明整屏根（`h-screen … overflow-hidden`），`:4-33` 是左栏 `w-72`（品牌 + `<NavList />` + 本页自己的列表槽 `<slot name="list" />` + 用户区），
`:36-38` 是右栏 `<main class="main-panel flex-1 min-w-0">` 承载默认槽；登出只在 `PageShell.vue:58-65` 实现一次。
六个受控视图（`SmartRobot` / `CallRecords` / `Org` / `Apps` / `Admin` / `Billing`）都包在 `<PageShell>` 里，
**不再自声明整屏根、不内联导航按钮、不各写一份 `handleLogout`**——这条形状由 `scripts/frontend/check-page-layout.mjs` 逐页判定。
`ChatRobot.vue`（`/chatrobot`）是唯一未走壳的已登录页：它没有站内入口、必须带 `?assistantId=`，本轮刻意不动（边界见手册 6.6）。
`App.vue` 只挂全局通知与 `<RouterView :key="route.path">`（**换路由即整页重挂**，
所以每个视图的 `onMounted` 一定会重跑；副作用是**通话中点导航会中断通话**，同一条边界）。

各页的**弹层**同属一处单点（v2.65）：遮罩用 `style.css` 的 `.geek-modal-mask`（自己就是竖向滚动容器）、卡片用 `.geek-modal-card`（`margin: auto` 安全居中），**不再内联 `fixed inset-0 … items-center`**；背景与 z 层仍由各页自己写，右栏内容一律带滚动出口（`flex-1 min-h-0 overflow-y-auto`）。这些都由布局门禁第 6 组逐页判定。

## 2. SmartRobot.vue —— 语音工作台（全站最重的视图）

- **区域**：左栏由壳提供，`#list` 槽里放 新建助手按钮 + 助手搜索框 + 助手列表（含空态与分页）；右栏（默认槽）依次为
  右工具栏（名称、设置、刷新、搜索、导出、快捷命令、主题）→ 聊天区（搜索条、欢迎空态、ASR 文本条、`ChatMessages`）→
  输入区（语音面板 ⇄ 文本面板）；
  弹层依次为 新增助手 + 删除确认、**设置弹窗**（六张纵向卡片：助手信息 → 人设 → 音色 → 模型参数 → 可用工具 → 知识库）、
  知识库管理（列表 + 文件管理）、创建知识库、检索效果测试。
  品牌 / 导航 / 用户区 / 登出**不在本视图内**（v2.63 起归壳）；首屏骨架屏分支（`pageLoading`）刻意留在壳外自声明整屏根——它是整屏占位，不该半屏。
- **数据**：`api/assistant.ts`（分页/增删改/音色/模型/工具字典）、`api/ragflow.ts`、`api/auth.ts`（登出）、
  `api/callRecord.ts`（仅 `uploadRecording`）；两条 WS：`/ws/{assistantId}`（**不带 sessionId**）与
  `/ws-voice/{assistantId}`，开链后延时 100ms 发 `{type:'selectedKbIds'}`。
- **纯模块复用**：`utils/chatFrame.ts`（回合帧单点分派）、`utils/recordingUpload.ts`、`utils/exportChat.ts`、
  `composables/useWebRTC` / `useMessageSearch` / `useQuickCommands` / `usePersonaTemplates` / `useNotification` / `useTheme`。
- **状态口径值得注意**：
  - 人设保存做**两件事**：WS 热更新 + `updateAssistant()` 持久化（`ChatRobot.vue` 只做前者，见第 3 节）。
  - 知识库选择是**双来源**：服务端 `assistant.knowledgeIds` 优先，`localStorage['knowledgeBaseSelection_<id>']` 兜底；
    该字段可能是 JSON 字符串或数组，客户端归一。
  - 工具白名单：空集合 = 全部已注册工具；字典来自 `GET /api/tools`（只含配置就绪的工具）。
  - `onMounted` 依次 `await` 音色 → 模型 → 助手 → 知识库，`finally` 里一定清 `pageLoading`
    （**加载失败也让页面可见**，不留骨架屏）。
  - 挂断时先取录音 blob 再 `hangup()`，`finishRecordingUpload` **刻意不 await**（通话收尾不等网络）。
  - WebRTC `connectionState === 'failed'` → 提示 + 结束通话，即语音降级为文本。
  - 导出用原生 `confirm()` 当二选一（确定 = Markdown，取消 = JSON）——现状如此，不是笔误。
  - 消息搜索作用于内存 `messages`，**不是服务端搜索**。

## 3. ChatRobot.vue —— 单助手调试页

- **区域**：左栏（助手信息 / 会话列表含新建·置顶·删除 / 人设编辑 / 知识库选择摘要）→ 右栏（头部含"语音通话中"与重置、
  ASR 条、"加载更早消息"、`ChatMessages`、输入区语音⇄文本）；弹层为知识库管理与创建知识库。
- **入口契约**：`route.query.assistantId` 必需，缺失即提示并 `router.back()`；可选 `sessionId`，缺则自动建会话。
- **与 SmartRobot 的三处实质差异**（不是复制粘贴的意外，但容易踩）：
  1. 人设保存只发 WS `prompt`，**不落库** ⇒ 该页改的人设不持久。
  2. 会话是这里的一等概念（WS URL 带 `?sessionId=`，每收到 `query_end` 刷新会话列表，因为标题可能首轮自动生成）。
  3. 历史还原走 `utils/mapHistoryRecord.ts`（SmartRobot 不使用它），分页 50 条、服务端新→旧、客户端 reverse 后前插。
- 删除会话用原生 `window.confirm`；删除当前会话会自动建一个替代会话。

## 4. CallRecords.vue —— 记录与用量

- **区域**（v2.63 起走壳）：左栏 `#list` = "记录（N）"标签 + 记录列表行（分页 10 条，`totalPages > 1` 才显示分页条）+ 空态；右栏 = 页头 →
  用量统计（日/周/月切换，默认 `week`；**统计范围下拉仅在拉到组织时渲染**，选项为"我的用量"与各组织，v2.67 起；三张卡 + 纯 CSS 柱状图 + 一行口径说明）→
  **选中记录的详情**（录音回放 + 消息气泡，与列表同屏）。
  改版前详情是遮罩弹窗，现在不再有弹窗 ⇒ "选中哪条"从弹窗开关变成了左列表的状态，选中行用 `.geek-listrow--active` + `aria-current="true"` 表出。
- **口径**：`status` 数值枚举 `0 失败 / 1 进行中 / 2 正常结束 / 3 中断`；`REC` 角标依据后端返回的 `recording` 布尔。
  柱高按当期最大值归一（除数钳到 ≥1）。口径说明条是**从响应里读出来的**（`scope` / `excludedCalls` / `archivedCalls`），页面不自己算也不自己写死规则。
- **录音是惰性的**：点"播放录音"才 `fetchRecordingBlob`，打开与关闭都 `revokeObjectURL`。
  该 API 返回裸 `Blob` 并**丢弃服务端 message**，失败抛固定文案。
- 气泡是页面内手写的，不是 `ChatMessages`；`messages[].role` 数值 `0 = 用户`（右对齐）。
- ⚠️ 顶部"用量统计"自 v2.67（C-134）起是**近 1/7/30 天已结算通话及其对话轮次**的聚合：只计正常结束/中断，消息数取自 `records` 而非反列，
  活表与归档表并读，组织作用域按成员集合聚合（非成员 403）。四点口径与其边界登记在手册 2.6 与 6.6；"用量"一词仍宽于该接口
  （文本轮次与工具开销不在内，见 7.4 S-18）。

## 5. Admin.vue —— 管理后台

- **区域**（v2.63 起走壳）：左栏 `#list` = 五个面板入口（`Admin.vue:462` 的 `panels` 常量，逐项带图标与 `aria-current`）；右栏 =
  概览六卡（用户/助手/通话/消息/会话/审计，**始终在顶部**）→ 当前面板内容：**审计日志 · 用户列表 · 数据归档 · 配额管理 · 邀请码**
  （默认 `audit`；模板是两条 `v-if` 链，归档独立一条，其余一条）。原"顶部 Tab 条"随改版消失，面板名单从此只有一处。
- **数据**：只有 `api/admin.ts`；`onMounted` 用 `Promise.all` **并行发六个请求**，与当前 Tab 无关。
- **分页**：三套独立页码（审计 / 用户 / 邀请码），`PAGE_SIZE = 10`，总数 ≤10 时不显示分页条；
  只有用户列表有关键词搜索（搜索即回第 1 页）。
- **配额**：`loadQuotas` 顺带 `fetchUsers(1, 200)` 填用户下拉；组织作用域 ID 是**手填 UUID**；
  数字框留空 = "该维度不改"，`0` = 硬拦；编辑键是合成的 `${scopeType}:${scopeId}`。
- **邀请码**：单次最多 50 个（客户端与服务端常量一致）；**码只在生成响应里出现一次**，之后只能看台账；
  复制在有 `navigator.clipboard` 时用它，否则回退 `textarea.select()`（非安全上下文没有 clipboard API）。
- 无 `window.confirm`：归档/配额/邀请码操作即刻执行。校验类错误走行内文案，加载失败类走通知。

## 6. Org.vue —— 组织管理

- 两级主从（v2.63 起落在壳的左右两栏）：左栏 `#list` = "我的组织"标签 + 创建按钮 + 组织列表行；右栏 = 选中组织的成员面板。
  创建组织、添加成员各一个弹窗。改版前"点组织卡片→看成员→返回列表"的往返消失，`返回列表` 按钮随之删除（判据在布局门禁里）。
- 角色矩阵在 UI 层由 `myRole === 'owner'` 控制（删除组织 / 添加成员 / 改角色 / 移除）；非 owner 在自己的行上看到"退出"。
  `myRole` 由"成员列表里有没有我"推出，`localStorageUserId` 在 setup 时读一次（换路由整页重挂掩盖了它不响应重登的问题）。
- 无分页、无搜索（接口返回全量）；成员变更后整表重取。
- 三处原生 `window.confirm`：移除成员、退出组织、删除组织。
- 添加成员的失败走行内 `memberError`，其他失败走通知——现状不一致，改动时留意。
- 成员列表按 `username` 邀请（不是 id）；列表卡片角标 `OWNER/MEMBER` 表示的是"是不是我建的"，不是该成员角色。

## 7. Billing.vue —— 用量配额（只读）

- 三组卡片（助手数量累计 / 单日通话次数与时长 / 单日消息量）+ 作用域行（`scopeType === 'org'` ⇒ 显示"组织级"）+ 刷新按钮。
- 六页里唯一**不填 `#list` 槽**的：它没有"要浏览的条目集合"（配额行是同一份数据的不同维度），左栏就此只有品牌 + 导航 + 用户区。
  布局门禁专门锁这一条"不硬造空列表"（`check-page-layout.mjs` 第 3 组）。
- 最薄的视图：`onMounted` 一次请求，无分页/搜索/确认。
- **没有真正的空态**：读不到就到处是 `'-'`，所以加载失败时必须靠通知说清"显示的不是实时额度"（已如此实现）。
- 时长格式化在前端（秒 → "N 分 N 秒"）。

## 8. Apps.vue —— 开放平台应用

- **区域**（v2.63 起走壳）：左栏 `#list` = "应用（N）"标签 + 创建按钮 + 应用列表行（名称 + 能力串摘要）；右栏 = 顶部一行"无法归属的拒绝"
  + **选中应用的详情卡**（应用名称 / 作用域 / Webhook / 近 N 小时被拒 / 状态 / 创建时间 / id / 吊销与编辑能力入口），
  改版前那张"每行一列"的表格消失，无分页的全量列表改为逐条选中。
- 四个**动作**弹层保留（创建、创建成功的一次性凭据、编辑能力、吊销确认）——它们是流程而非浏览，不进左栏；遮罩与居中走 `.geek-modal-mask` / `.geek-modal-card` 共享类（v2.65），背景仍是该页原来的行内 rgba；
  未选中应用时右栏给"从左侧选择一个应用"的引导态。
- **三态拒绝台账**（`utils/denialLedger.ts`）：`unavailable`（"台账读不到"）≠ `quiet`（"0 次"）≠ `denied`（按种类出 chips）；
  读取失败刻意不渲染成 0，且不影响应用列表。
- 能力以**逗号串**存取，客户端 `split` 后交给共用组件 `ScopePicker`（创建与编辑同一份）；
  编辑保存是**整串替换**，全不选时禁用保存；创建默认 `['chat']`。
- 吊销用页面内确认弹窗（不是 `window.confirm`）。
- ⚠️ 后端形状有两个坑：列表项字段名是 `scope`（单数）而创建响应是 `scopes`；`denials` 行里 `app: null` 表示"认不出归属"。
  能力选项在前端硬编码，注释写明必须与服务端 `ApiApp.ALL_SCOPES` 的名称与顺序一致。

## 9. Login.vue / Register.vue / NotFound.vue

- **Login**：**一个"账号"输入框接受用户名 / 邮箱 / 手机号三态**（v2.89；界面文案改口，请求体字段名仍是 `username`，判态由服务端 `IdentifierPolicy` 单点做，浏览器不复制任何格式正则）+ 密码（可见性切换）→ `saveSession` → `router.push('/smartrobot')`；
  读 `route.query.reason === 'expired'` 预填"登录状态已过期"（这是 `api/auth` 不可续期时跳回来的对端）；
  错误只走行内，**不用通知**；`@keyup.enter` 与 `@submit.prevent` 双保险。
- **Register**：用户名 + 密码 + **邮箱 / 手机号两个选填项**（v2.89，`trim() || undefined` 上线，空串＝未填写；`canSubmit` 不含这两项，写进门禁 [17] 组）；是否需要邀请码由公开端点 `GET /api/auth/register-config` 探测，
  **`inviteRequired` 默认为 `true`**（探测失败宁可多一个字段），三处文案随该开关切换（响应里的 `mode` 目前**无人消费**，见手册 6.6）；
  校验分两步（先必填，再按顺序查两次密码一致与长度），空邀请码发 `undefined`，大小写与分隔符归一、格式合法性都在服务端。
  注册成功即自动登录。
- **NotFound**：纯展示，无逻辑、无 API、甚至不渲染主题开关。

## 10. 全局挂载与启动顺序

| 位置 | 内容 |
|---|---|
| `main.ts` | 先同步应用主题（读 `localStorage['yunyu-theme-mode']`，默认 `system`）**再** `createApp`（防闪白）→ 给 `<html>` 加 `.theme-transition` → 注册 `matchMedia` 监听（仅在模式为 `system` 时生效）→ 挂 `#app`。无插件、无全局状态库。 |
| `App.vue` | 唯一的全局通知提示（`useNotification` 模块级单例驱动）+ `<RouterView :key="route.path">` |

## 11. 后端形状依赖速查（前端真正读回来的东西）

| 来源 | 形状 | 被谁依赖 |
|---|---|---|
| 所有 REST | `{code, message, data}`；`code !== 200` 即抛 | `api/*` 的 `request()`；**例外**：`api/ragflow.ts` 打的是 `/api/ragflow` 代理，它透传 RAGFlow 原始响应（成功码是 `code: 0`），故自带 `RagflowApiError` 而不复用 `parseResponse` |
| 分页 | `{list, total, page, pageSize}` | 助手 / 审计 / 用户 / 邀请码 / 通话记录 |
| 会话消息分页 | `{list, pageSize, total}` + 每条 `role` 数值 `0/1/2/3` | `utils/mapHistoryRecord.ts` |
| `knowledgebase` | 对象或 `null`（**不是空对象**） | `utils/knowledgebaseFlag.ts` → 消息角标 |
| `query_end` 帧 | `{message, costTime, knowledgebase, tokenUsage}` | `utils/chatFrame.ts` |
| `webrtc_connected` 帧 | 必须带 `callId`，否则录音无处挂靠 | 两工作台视图的通话收尾 |
| `denials` | `{windowHours, rows[]}`，行内 `app: string \| null` | `utils/denialLedger.ts` |
| `register-config` | `{inviteRequired, mode}`（v2.89 补 `mode`；`Register.vue` 只读 `inviteRequired`） | `Register.vue` |

## 12. 相关文档

- [ARCHITECTURE.md](ARCHITECTURE.md) — WS 通道与鉴权链路
- [../DESIGN.md](../DESIGN.md) — 控件与状态色规范
- [REGISTRY.md](REGISTRY.md) — 门禁脚本清单（哪些页面有桩测覆盖）
