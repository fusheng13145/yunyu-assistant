# DESIGN.md — 界面视觉与交互规范

> 适用对象是**产品界面本身**（登录后的一整套工作台），不是营销官网——本仓没有官网，也不打算做落地页。
> 权威细节在手册 [第 1 章](docs/云谕助手项目手册.md) 与各页说明；本文只登记"看得见的东西该长什么样、为什么"。
> 代码来源：`frontend/src/style.css`（CSS 变量 + `.geek-*` 全局类）与 `frontend/tailwind.config.js`（token 映射）。

## 1. 风格定位：纯白极客

`style.css` 与 `tailwind.config.js` 的注释里，这套风格就叫 **"纯白极客"**。取舍是明确的：

- **高对比黑白为主色**，不用渐变、不用玻璃拟态、不用彩色大面积铺底。主色是近黑墨色 `#18181B`（暗色下反转为 `#FAFAFA`），白底黑字承担绝大部分视觉重量。
- **点缀只有一个科技蓝** `#2563EB`（暗色 `#3B82F6`），用于强调、链接态、输入聚焦。语义色（成功/错误/警告/信息）各一档，都配 8% 左右的透明底（`--geek-*-bg`）以免大面积高饱和。
- **1px 硬边框 + 平涂卡片 + 小圆角**（`--radius-sm:4px / md:8px / lg:12px / xl:16px`，注释原话"极客风：更硬朗的小圆角"）。阴影克制，只有 `--geek-shadow-md/lg` 两档且颜色本身带透明度。
- **等宽字体承担"技术感"**：数字、ID、耗时、Token 数、角标一律 `JetBrains Mono`（`.mono` / `.geek-badge`），正文 `Inter` + 中文回退 `PingFang SC / Microsoft YaHei`。
- **两处刻意保留的极客符号**：蓝图网格背景 `.geek-grid-bg`（登录/注册等展示页）与终端闪烁光标 `.animate-blink`（流式输出中）。它们是全站的"签名"，新增页面不要另起一套。

## 2. Token 与颜色用法

颜色**只能走 CSS 变量**，不写死十六进制。两种等价写法：

| 写法 | 例 | 适用 |
|---|---|---|
| Tailwind token 类 | `text-geek-text-muted`、`bg-geek-input-bg`、`border-geek-border` | 模板里首选 |
| 裸变量（内联 style） | `style="color: var(--geek-text)"` | 动态取值（如按状态切色）时才用；`components/ChatMessages.vue` 里已大量存在 |

`tailwind.config.js` 为兼容历史写法保留了同义别名（`muted` ≡ `text-muted`、`secondary` ≡ `text-secondary`、`subtle`/`bg-subtle` 都指 `--geek-bg-subtle`），因此 `text-geek-muted` 与 `text-geek-text-muted` **渲染结果相同**。

> ⚠️ 这是一处**已知重复**，不是两套设计系统。新代码统一用 `*-text-*` 全名；`style.css` 里另有一小批手写的 `text-geek-muted` / `bg-geek-subtle` 工具类属遗留，改动时**顺手统一**但不要为统一而开独立批次（收益低于风险）。

暗色由 `darkMode: 'class'` 驱动：`<html class="dark">` + `composables/useTheme.ts`，模式（`light|dark|system`）存 `localStorage['yunyu-theme-mode']`，`main.ts` 在挂载前先应用一次以避免闪白，并跟随 `prefers-color-scheme`。**新增颜色必须同时给亮/暗两个值**（`style.css` 的 `:root` 与 `.dark` 成对），漏一个就是一半用户看不见。

## 3. 控件规范

共享控件是 **`style.css` 里定义一次的全局类**，不是各页各写一份的组件：

| 类 | 用途 | 注意 |
|---|---|---|
| `.geek-btn` + `-primary` / `-ghost` / `-danger` / `-sm` | 全部按钮 | 禁用态已定义（`:disabled`），别自己写 opacity |
| `.geek-card` / `.geek-card-elevated` | 卡片 / 弹窗容器 | 弹窗固定为 `animate-modal-in geek-card-elevated … rounded-xl shadow-lg`，宽度用 `w-[...] max-w-[95vw] max-h-[85vh]` |
| `.geek-input` | 所有输入控件 | 聚焦环走 `--geek-input-focus-*` |
| `.geek-badge` | 技术标签（等宽） | 只用于计数、状态、ID 一类短文本 |
| `.geek-notification` + `--success/--error/--warning/--info` | 通知条底色 | 由 `App.vue` 唯一挂载点渲染 |
| `.geek-divider` / `.geek-surface` / `.geek-scroll` | 分隔线 / 面板底 / 细滚动条 | 长滚动容器加 `geek-scroll`，否则默认滚动条与风格脱节 |

组件层（`frontend/src/components/`）目前只有四个真实组件：`ChatMessages.vue`（消息列表渲染）、`ScopePicker.vue`（开放能力勾选）、`SkeletonLoader.vue`（骨架屏）、`ThemeToggle.vue`（亮/暗/系统三态）。

**抽新组件的判断标准是"第三次"**：同一形状第一次出现在视图里不算重复，第二次容忍，第三次必须抽出（本仓已经因为"两份逐字相同的通知实现""两份内联历史角色映射"开过两次收口批次，见手册 7.4 的 C-89 与 C-91）。

## 4. 布局约定

- 主工作台（`/smartrobot`、`/chatrobot`）为**三栏**：左侧会话/助手列表、中间对话流、右侧或抽屉为配置面板；窄屏靠 `max-w-[95vw]` 的弹窗承接，不做响应式折叠动画。
- 管理类页面（`/records`、`/org`、`/billing`、`/apps`、`/admin`）为**单列卡片 + 表格**，页头统一 `标题 + ThemeToggle`。
- 间距走 Tailwind 刻度，实测最高频的是 `gap-2 / gap-3 / gap-1.5`；**没有自定义 spacing 刻度**（`tailwind.config.js` 只扩了 `colors` 与 `fontFamily`），不要为此引入新刻度。
- 骨架屏只在首屏加载占位（`SkeletonLoader.vue`），后续刷新保留旧数据 + 局部 loading，避免整页跳动。

## 5. 交互与状态口径（比颜色更要紧）

这一节是本仓**被缺陷反复教育后**形成的硬性口径：

1. **失败必须可见**。任何写操作失败、加载失败、被服务端拒绝，都要有用户可见提示（`useNotification`），`console.error` 可以保留但不是替代。只落在控制台 = 缺陷（手册 6.6 的吞错族）。
2. **"读不到" ≠ "没有数据"**。空态与故障态必须分家，UI 上做成三态而非两态：`unavailable`（读数请求失败）/ `quiet`（确认为空）/ `有内容`。参照 `utils/denialLedger.ts`（拒绝台账）与 `utils/recordingUpload.ts`（录音上传结局）。
3. **服务端拒绝要解冻输入**。文本/语音 WS 的 `error` 帧必须既提示又解锁输入框，统一经 `utils/chatFrame.ts` 的 `handleChatTurnFrame`，不要在视图里内联分派。
4. **知识库角标是三态**：检索失败 / 有引用 / 未检索（`utils/knowledgebaseFlag.ts`），其中"失败优先"，且历史行缺字段判为"未检索"而不是"失败"。
5. **状态色不单独承载信息**：错误/警告一律同时给文字，色觉与环境光不可依赖。
6. **动效只用于反馈状态**，不用于装饰：`animate-modal-in`（0.25s）、`animate-fade-up`（0.35s）、`animate-pulse-dot`（处理中）、`animate-blink`（流式）。新增动效不得超过 350ms，且不得影响可读性。

## 6. 无障碍现状（如实登记，勿当已达标）

现有实现是**薄的**，改前端时按"只增不减"执行：

- 已有：`ThemeToggle` 的 `role="radiogroup"` + `role="radio"` + `:aria-checked`；登录/注册显示密码按钮的 `:aria-label`；通知条 `role="status"`；`.geek-btn:focus-visible` 与 `.geek-input:focus` 两处焦点样式。
- 缺失（**新代码必须补，历史代码不强制返工**）：`<label>` 与 `<input>` 的 `for`/`id` 关联（当前为 0）、`alt` 文本、显式 `tabindex` 与模态框焦点陷阱、`Esc` 关闭弹窗、除按钮/输入框之外的焦点环。
- 底线：可点击的东西必须是真按钮（不是 `div @click`），键盘要能走完主要流程，正文对比度不低于现有 `--geek-text-*` 层级。

## 7. 禁止清单

- 新依赖：`frontend/package.json` 的运行期依赖只有 `vue`、`vue-router`、`lucide-vue-next`。**加 UI 库/图标库/动画库需用户批准**，图标一律用 lucide。
- 紫色渐变、玻璃拟态、假仪表盘、模板化 emoji 装饰——与本仓风格不符，且历史上被判定为"AI 生成感"来源。
- 写死颜色值、写死圆角、写死阴影；绕过 `.geek-*` 全局类另起一套按钮。
- 只改亮色不改暗色；只在成功时更新 UI。
