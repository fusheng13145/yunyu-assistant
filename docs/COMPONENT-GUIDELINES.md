# 组件与实现规范（COMPONENT-GUIDELINES）

> 写代码时的具体约束。协作与流程约束（批次、提交、推送、验证纪律）在 [AGENTS.md](../AGENTS.md)，
> 视觉与交互规范在 [DESIGN.md](../DESIGN.md)，本文只讲"组件怎么写才站得住"。

## 1. 依赖策略

| 侧 | 运行期依赖 | 加依赖的规则 |
|---|---|---|
| 后端 | Spring Boot 3.5 / Java 21、MyBatis-Plus、Spring AI 1.0、JJWT、OkHttp、Actuator | 认证用自研 `HandlerInterceptor`，**不引入 Spring Security**；不引入 DTO 映射层 |
| 前端 | `vue` `vue-router` `lucide-vue-next`（三个） | 不引入状态库、不引入 axios、不引入测试框架；新增依赖需用户批准（属扩面而非收口） |

Node 门禁脚本（`scripts/check-*.mjs` / `check-*.py`）**零新增依赖**是硬约束：它们靠 Node 原生 TS 类型擦除
直接 import 生产模块，而不是搭测试运行器。

## 2. 后端组件

### 2.1 命名与位置

沿用[手册 4.3 命名与编码约定](云谕助手项目手册.md#43-命名与编码约定)。要点：
`XxxController` / `XxxService` / `XxxMapper` / 实体名单数名词；判据类工具放 `util/`（无状态、可单测）；
定时任务放 `task/`；AI 工具一类一能力放 `tool/`。

### 2.2 Controller

- 只做参数收口、归属校验、响应封装；业务在 Service。
- 一律返回 `ApiResponse<T>`。**注意两条不同的拒绝语义**：管理/业务侧拒绝走 `ApiResponse.paramError`
  （HTTP 200 + body `code:400`），开放侧拒绝走真 401/403/429。写冒烟断言时别把两者混为一谈。
- 新增查询接口**必须**带 `userId` / `orgId` 条件（数据权限在业务层显式判，框架不兜）。
- 数值型配置维度不得留 NULL 落库：新建行时以兜底值打底，否则下次阅读拆箱 NPE。
- 直接注入 `*Mapper` 的管理端接口需要单独补测（它是唯一允许这么做的层）。

### 2.3 Service

- 第三方调用（LLM / RAGFlow / 网关 / 出站 HTTP）封装超时与降级；**SSRF 敏感的统一走
  `ExternalUrlValidator`，且用关闭重定向的 `webhookRestTemplate` 实例**。
- "失败"必须是可区分的状态，不能压成空结果。范式：`KnowledgeHit.failed` —— 检索失败与"知识库确实没答案"
  逐字节同形曾是不可发现缺陷。
- 判定类逻辑一律单点（见 [ARCHITECTURE.md 第 6 节](ARCHITECTURE.md#6-三条贯穿全仓的设计决定)），
  新增调用点复用判据而不是重写判据。
- 配额/凭据这类并发敏感操作：**单条带条件的原子 UPDATE**，0 行影响即拒绝；不许"先查后改"。

### 2.4 Entity（本项目的特殊约定）

实体直接外发，所以实体即契约：

- 凭据列的**外发抑制加在实体上**（`getPassword()` 级 `@JsonIgnore` + `toString()` 不含该字段），
  不要在每个出口手写 `setXxx(null)`——手写会漏，且会**改写查出来的行**（二次 `updateById` 把哈希写成 NULL）。
- 承载列表的 JSON 字符串列：列访问器 `@JsonIgnore`，另给一个派生 getter 折成对象；
  **列缺失/空/脏值一律读成 `null`，不臆造"看起来正常"的默认对象**。
- 逻辑删除用 `@TableLogic`；主键 `IdType.ASSIGN_UUID`；审计字段用 `FieldFill` 自动填充。

### 2.5 异常处理

- 业务异常用 `common/` 的两个类型；HTTP 状态映射只在 `GlobalExceptionHandler`。
- 新增映射时**同时补一条"路由判据"**：用 `ExceptionHandlerMethodResolver.resolveMethod()` 断言该异常
  解析到的处理器方法名。只断言状态码抓不到"被 `Exception` 兜底抢走"这种形状。
- 客户端请求形状错误（405/415/400/413/406/404）必须是 4xx 且不打全栈日志——它们会计入服务端错误率。

### 2.6 审计

- 需要留痕的写操作加 `@Audit`；成败判据在切面一处（抛异常 **或** 返回体业务码 ≠ 200 都记失败）。
- 变更类操作把"改前 → 改后"经请求属性交给切面，且切面必须在 `proceed()` **之后**读。
- 开放侧（`/api/open/**`）的拒绝**不逐条写库**，走 `OpenApiDenialMeter` 聚合台账；认不出归属的事件折成一格。

### 2.7 单测手法（本项目已验证的四条）

1. **判据用内存假表，不用 Mockito 打桩**：`Fake*Mapper extends *MapperStub`（机械导出的"实现全接口但一律拒绝调用"
   的底子）。判据的实质是"行在不在、列值怎么读"，假表能建模，打桩只会把判据形状由桩返回值决定。
   MP 升级新增抽象方法时 Stub 在编译期立刻报错，而不是让测试静默失去覆盖。
2. **接线类判据注入真实组件**：真实 `ClientIpResolver` / 真实 `OpenApiDenialMeter`，桩会把"到底有没有走这条判据"
   一起桩掉。
3. **链式用例串真实对象**：真实 `JwtUtil` + 真实判定服务 + 真实入口方法，因为用户感知的性质是端到端的那句话，
   不是某方法返回 true。
4. **不加载 Spring 上下文**（唯一的 `@SpringBootTest` 只做装配冒烟）：所以被测类每个 `@Value` 键必须在测试里显式给出；
   需要 `LambdaQueryWrapper` 时按既有做法在 `@BeforeEach` 里初始化 TableInfo。

**禁止的验证方式**：把安全护栏改回不安全实现的"变异测试"（见 AGENTS.md 安全与验证纪律）。

## 3. 前端组件

### 3.1 什么该抽成组件

- 判据是**"第三次出现必须抽"**：前两次复制粘贴在本仓已各产出一次真实缺陷（通知实现两份、历史映射两份、
  回合帧分派四份），修复成本远高于早抽。
- 现有 6 个共享组件：`ChatMessages`（消息流渲染，`props: messages/autoScroll` + `emit: scroll-state-change`）、
  `NavList`（左栏站点导航六项，`adminOnly` 项按 `localStorage.role` 过滤，无 props）、`PageShell`（布局壳，
  `prop: subtitle` + `slot: list`（本页的浏览列表）+ 默认槽（右栏内容）；整屏根、品牌、导航、用户区、登出**只在它内部一次**）、
  `ScopePicker`（能力勾选，`v-model` 数组 + `options`）、`SkeletonLoader`、`ThemeToggle`（`v-model` 主题模式）。
- 在**六个受控视图**的范围内，布局壳是唯一声明整屏根（`h-screen … overflow-hidden`）的地方；视图里再出现自声明的整屏根即视为回归，
  目前唯一的豁免是 `SmartRobot.vue` 的首屏骨架屏分支（整屏占位不该半屏），由 `check-page-layout.mjs` 逐条点名。
- **纯逻辑一律抽成 `utils/` 里的函数而不是组件内联**：能被门禁 import 的模块必须是
  **零运行时相对 import**（只允许裸包名与 `import type`）。这是硬约束，违反会让桩测写不出来。
  现有 7 个 util 模块满足；`utils/websocket.ts` 因运行时依赖 `api/auth` 而不满足。
- **弹层的定位形状也只有一个单点**（v2.65）：`style.css` 里的 `.geek-modal-mask`（`position: fixed; inset: 0; display: flex; overflow-y: auto` ⇒ 遮罩自己就是滚动容器）+ `.geek-modal-card`（`margin: auto` ⇒ 够则精确居中、不够则贴顶且上下都滚得到）。视图里**不要**再在遮罩元素上写 `fixed inset-0 … items-center justify-center`——那是**不安全居中**，内容一高于视口就会把卡片上下一起裁掉，而向上溢出的部分滚不出来（手册 7.4 候选 ㊾ 的实测读数就是主按钮点不到）。`margin: auto` 在交叉轴两侧还会抑制 `align-items: stretch`，所以**不需要内层包裹 div**，`@click.self` 的"点遮罩关闭"语义得以原样保住。背景与 z 层仍留给各弹层自己写（本仓刻意未统一）。判据在 `check-page-layout.mjs` 第 6 组。

### 3.2 视图（views）写法

- 页面局部状态用 `ref`；跨视图共享的东西只有通知（模块级单例）与会话凭据（`api/auth.ts` 单点 + `localStorage`）。
- **失败必须可见**：`catch` 里不许只留 `console.error`。正确形状是 `console.error` **保留** + 同块内
  `showNotification(..., 'error')`；高频/纯诊断路径（音频电平、WS 帧解析细节）可以只留日志，但要写明理由。
- **不要只在成功时更新 UI**：加载失败时要把界面留在可读状态并明说"显示的不是实时数据"。
- 三态优于布尔：`unavailable`（读不到）≠ `quiet`（确实是 0）≠ `denied`（有值）。任何"缺字段"都不许
  渲染成"正常但为空"。
- 路由守卫只在 `router/index.ts` 一处；新页面需要登录就加 `meta.requiresAuth`，管理员页加 `requiresAdmin`。
- 站点导航只有 `<NavList>`（由 `PageShell` 挂载）一处；新页面要能被走到，就在 `NavList.vue` 的 `NAV_ITEMS` 加一项并按需给
  `adminOnly`，**不要在视图里内联导航按钮**（那就是第二份会腐烂的清单，布局门禁第 2 组直接判红）。
  `/chatrobot` 至今没有任何站内入口，只能手输地址——现状如此。
- 新页面若属于"浏览条目 + 看详情"的形状，就包 `<PageShell>` 并把条目列表放进 `#list` 槽；
  列表行用 `.geek-listrow` / `.geek-listrow--active`（形状单点在 `style.css`），选中项同时给 `aria-current="true"`，
  并且**必须是真 `<button>`**（不是 `div @click`）。没有条目集合的页面（如 `/billing`）就只留导航，不要硬造空列表。

### 3.3 样式

- 只用 `style.css` 里的 `--geek-*` / `--radius-*` 令牌与 `.geek-*` 全局控件类；**不写死颜色、圆角、阴影**。
  细节见 [DESIGN.md](../DESIGN.md)。
- 新颜色必须**成对给亮色与暗色**（暗色是 `class` 策略，`html.dark`）。
- Tailwind 调色板里存在两套别名（`muted` 与 `text-muted`）：新代码用 `*-text-*` 全名，遗留用法不单独开批次重排。
- `v-html` 目前只有两处（Markdown 渲染、图标 SVG），且 `utils/markdown.ts` 入口即全量转义；
  新增 `v-html` 需要说明内容来源与转义路径。

### 3.4 无障碍（现状如实登记）

已具备：`radiogroup` + `aria-checked`（主题切换）、若干 `aria-label`、`role="status"` 的通知区、两处焦点样式、
左栏列表的选中语义（导航项 `aria-current="page"`、主从列表选中行 `aria-current="true"`，v2.63 起由布局门禁判定）。
**明确缺失**：表单几乎没有 `label for`（当前为 0）、图片无 `alt`、无 `tabindex` 管理、
弹窗无焦点陷阱与 Esc 之外的键盘收口（只有两个工作台视图有全局 Esc 关层）、多数交互元素无自定义焦点环、
`SmartRobot.vue` 的助手行仍是 `div @click`（存量，与手册 ㊾ 附带登记的同一条债）。

新增组件时的最低要求（不追求一次性补齐存量）：

1. 表单控件必须有可关联的 `label`（或 `aria-label`），不能只靠 placeholder。
2. 状态不能只靠颜色承载——用图标 + 文案（`.geek-badge` 已含文字）。
3. 自定义可点击元素改用 `<button>`；确有必要时才加 `tabindex="0"` + 键盘事件。
4. 弹层要能 Esc 关、打开时焦点进层、关闭后回触发元素（存量未做，新写的按这条写）。

## 4. 契约与类型

- 前端类型集中在 `types/index.ts`（前后端契约）；**视图内不得再写内联类型副本**——曾经因为副本挡住了一个字段透传。
- 后端新增对外字段时，同步三处：类型定义、`api/` 模块的响应接口、手册 7.1/7.2 的协议速查。
- 破坏性响应形状变更**不加兼容垫片**（历史决定：v2.48 直接把 `"password":null` 变成"无此键"），
  但要在手册写清"响应形状跨版本不一致"的界。

## 5. 门禁怎么配

| 改动 | 必须同时 |
|---|---|
| 后端判据/接线 | 单测 + 手册登记；新增拒绝语义要写反向锚点（"放行不记账""内部错误不入开放台账"这类） |
| 前端纯逻辑 | 一道 Node 桩测（`scripts/check-*.mjs`）+ `package.json` 的 `check:*` 脚本 + **纳入 `ci.yml` 的 frontend job** |
| 防复制粘贴 | 桩测里加**静态源码断言**（读视图原文，要求经统一入口、不得残留旧形状） |
| 页面形状（壳 / 导航 / 左列表 / 登出 / 弹层遮罩与居中） | `scripts/check-page-layout.mjs`（静态读六视图 + 两个新组件 + `style.css`）；新增或改名视图时要同步它的 `VIEWS` 清单与锚点，否则第 5 组反向锚点红 |
| 部署面/接口契约 | 跑一次真机 `scripts/smoke.sh`，并按"跑法口径"记项数（环境变量差异会改变项数，见 AGENTS.md） |
| 环境变量 | 同步 `application.yaml` 默认值 + `additional-spring-configuration-metadata.json` + `.env.example` + 手册 5.3 + README 表，否则 `check-config.py` 红 |
| 文档 | `python scripts/check-docs.py`（目录/表格/链接/遗留标记/变更记录连续性） |

## 6. 相关文档

- [PAGE-STRUCTURE.md](PAGE-STRUCTURE.md) — 每个页面的实况与后端形状依赖
- [DEVELOPMENT.md](DEVELOPMENT.md) — 命令、顺序、批次流程
- [REGISTRY.md](REGISTRY.md) — 登记表类东西（工具、能力、门禁、迁移）在哪几处
