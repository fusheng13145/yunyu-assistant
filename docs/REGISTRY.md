# 登记表（REGISTRY）

> 本仓**没有组件包分发**，也没有 `REGISTRY.md` 模板假设的那套构建/发布流水线。
> 这里登记的是仓库里**真实存在的六张"登记表"**：它们共同的特点是——
> **"没登记就等于没生效/会被门禁判红"**，所以必须逐条有账本、有判据、有责任人视角。

| # | 登记表 | 位置 | 不登记的后果 | 谁把它变成硬约束 |
|---|---|---|---|---|
| 1 | AI 工具注册表 | `backend/.../tool/` | 工具名重复 → **启动失败** | `ToolRegistry.init()`；`npm run check:registries` 核对第 1 节表与代码注册名双向一致 |
| 2 | 开放端点 → 能力表 | `interceptor/OpenApiAuthInterceptor.REQUIRED_SCOPES` | 新端点未登记 → **一律 403**（fail-closed） | 该拦截器 + 台账计数；`check:registries` 另核对表与 `REQUIRED_SCOPES`、握手处的 `SCOPE_VOICE` 判定、以及"限流 → 验 Key → 验能力"的先后 |
| 3 | 拒绝台账的"种类"表 | `service/OpenApiDenialMeter.Kind` | 新拒绝场景无处可记 → 只能混进别的格，读数失真（例如关闸存量被查不出来） | 两个拦截器单测逐格断言落点 + `npm run check:denial-ledger` 锁前端分格 + `check:registries` 锁"第 3 节表 ↔ 枚举 ↔ 文档计数"三者相等且前端不抄清单 |
| 4 | 数据库迁移账本 | `backend/src/main/resources/db/migrations/` + `schema_migrations` 表 | 实体有列而库没有 → 相关查询整体报错 | `scripts/db-migrate.sh`；`check:registries` 核对第 4 节表与目录实际文件同名同序 |
| 5 | 环境变量登记表 | `application.yaml` ↔ `.env.example` ↔ IDE 元数据 | 见第 5 节 | `scripts/check-config.py` |
| 6 | 门禁与脚本台账 | `scripts/` ↔ `frontend/package.json` ↔ `.github/workflows/ci.yml` | 未纳 CI 的检查＝只有人手工跑时才生效 | CI 的 `gates` / `frontend` job；`check:registries` 第 5 组核对"脚本 ↔ 本表 ↔ `package.json` ↔ `ci.yml` ↔ `AGENTS.md` 命令块"四处逐条对上（本批自己就被它判红过一次：先写了 CI 步骤、后补本表行） |

---

## 1. AI 工具注册表

**机制**：一个工具类 = 一个能力。新增只需写 `@Component` 类 + 暴露 `ToolCallback` Bean，
`ToolRegistry` 按 `List<ToolCallback>` 自动收集，**不需要在任何地方手工登记列表**；
工具重名启动即失败（否则后注册者静默覆盖先注册者）。

**启用门控**：类上标 `@RequiresProperty("key" 或 "key=value")`，依赖的密钥/开关未就绪时
`ToolAvailabilityCondition` 让整个类不装配 ⇒ **工具根本不暴露给模型**（此前是"暴露但执行时报错"）。

**按助手裁剪**：`assistants.tools`（JSON 数组字符串，实体折成列表）为白名单；
空 = 全部可用（既有助手零迁移即行为不变）；显式列表 = 精确集合，其中未注册的名字忽略并记 WARN，
**全部无效时该助手无工具可用——不回落"全部可用"**（避免配置错误反而放大权限）。
工具集在 WS 连接建立时快照，改配置需重进助手或下次通话才生效。

**字典端点**：`GET /api/tools` 只返回"当前真的注册了"的工具（前端勾选框的数据源）。

| 工具名 | 类 | 依赖（就绪条件） | 环境变量 |
|---|---|---|---|
| `get_current_datetime` | `DateTimeTool` | 无 | — |
| `hangup` | `HangupTool` | 无 | — |
| `web_search` | `SearchTool` | `app.search.api-key` + `app.search.endpoint` 非空 | `SEARCH_API_KEY` / `SEARCH_ENDPOINT` |
| `get_weather` | `WeatherTool` | `app.weather.api-key` 非空 | `WEATHER_API_KEY` |
| `generate_image` | `ImageTool` | `app.image.api-key` 非空 | `IMAGE_API_KEY`（+ `IMAGE_ENDPOINT` / `IMAGE_MODEL`） |
| `generate_video` / `query_video` | `VideoTool`（**一个类两个工具**） | `app.video.api-key` 非空 | `VIDEO_API_KEY`（+ `VIDEO_ENDPOINT` / `VIDEO_MODEL`） |
| `fetch_webpage` | `WebFetchTool` | `app.webfetch.enabled=true` | `WEBFETCH_ENABLED`（+ 上限 `WEBFETCH_MAX_BYTES`） |
| `deep_research` | `DeepResearchTool`（组合 `web_search` + `fetch_webpage`） | 搜索两项 + `app.webfetch.enabled=true` | 上述两组 + `RESEARCH_MAX_SOURCES` |

> 为什么"提交 + 查询"拆两个工具：工具调用在对话流内同步执行，不能在单个工具里轮询等出片。
> 图像/视频按 OpenAI 兼容契约实现，**尚未持有真实服务商账号完成过一次调用**（口径见手册 6.6）。

## 2. 开放端点 → 能力表

| 端点 | 所需能力 |
|---|---|
| `POST /api/open/chat` | `chat` |
| `POST /api/open/call` | `call` |
| `WS /api/open/ws-voice/{assistantId}` | `voice`（握手处判定 `SCOPE_VOICE`；**v2.52 起每次 `offer` 与每个 ASR 回合入口再复核一次**，故改小/吊销会终止进行中的通话） |
| `/api/open/callbacks/**` | 不走此表：由 `AppConfig` 从开放拦截器排除，独立校验 `X-Gateway-Token` |

- 能力全集：`ApiApp.ALL_SCOPES = [chat, call, voice]`（前端 `Apps.vue` 的选项列表必须与之**同名同序**，
  该约束写在代码注释里）。
- 判定顺序固定：**限流 → 验 Key → 验能力**。顺序反过来时试 Key 的预算不受任何约束（拿 401 挡住 429）。
- `scopes` 为 NULL/空串按**无能力**处理（fail-closed）；未登记的 `/api/open/` 路径按拒绝处理并记 WARN + 入账。
- 能力可变更：`PUT /api/openapi/apps/{id}/scopes` **整串替换**、下一个请求即生效；审计行记 `from`→`to`。
- 401 = 凭据不对；403 = 没授权；429 = 超限；三类拒绝都进 `OpenApiDenialMeter` 聚合台账（不逐条写库）。

## 3. 拒绝台账的"种类"表

`OpenApiDenialMeter.Kind` 七个枚举值即台账的行种类。每个值自带中文名，管理端直接显示后端返回的 `kindLabel`（前端不再抄一份清单，也就不会抄漏）：

| Kind | 含义 |
|---|---|
| `KEY_MISSING` | 请求根本没带凭据 |
| `KEY_INVALID` | 带了但查不到、或应用已停用 |
| `SCOPE_DENIED` | 凭据有效但能力没开通 |
| `ENDPOINT_UNREGISTERED` | 新端点忘了登记所需能力 |
| `RATE_LIMITED` | 落在限流桶外 |
| `URL_KEY_USED` | URL 查询参数带 Key 且通道**已开闸** ⇒ 放行但记账（长期凭据已进中间件日志） |
| `URL_KEY_REJECTED` | URL 查询参数带 Key 但通道**默认关闸** ⇒ 按未携带凭据拒 401 |

基数上限 = 7 种 × (应用数 + 1 个 `unknown` 格) × 24 小时桶；进程内计数、重启清零（该局限已登记为候选 ㉟）。

## 4. 数据库迁移账本

迁移文件按名顺序应用，已应用版本记在 `schema_migrations`；每条自带存在性守卫（守卫存储过程在
`db/migrate-helpers.sql`），重复执行为 no-op。

| 迁移 | 内容 | 可回退性 |
|---|---|---|
| `0001_org_scope_columns.sql` | 给 `assistants` / `knowledgebases` / `sessions` / `call_records` 等加 `org_id` + 索引 | 可（加列） |
| `0002_assistants_tools.sql` | `assistants.tools`（JSON 白名单，空=全部） | 可（加列） |
| `0003_quota_daily_usage.sql` | 新建判定账本表 `quota_daily_usage`（四元唯一键） | 可（建表） |
| `0004_invite_code.sql` | 新建 `invite_code`（一次性凭据，**无 `is_deleted`**） | 可（建表） |
| `0005_user_token_version.sql` | `users.token_version`。⚠️ 缺这一列的后果最重：签发直接抛（登录 500）、校验侧 fail-closed ⇒ **全站 401** | 可（加列） |
| `0006_api_key_hash.sql` | 回填 `app_key_hash`、加唯一索引、**删除 `app_key` 明文列** | **不可回退**：明文回填不出来，旧代码要读的列已不存在 |
| `0007_kb_dataset_unique.sql` | `knowledgebases.dataset_id` 加唯一索引；建索引前先 `yunyu_assert` "没有同一 dataset_id 的多行"，有冲突即中止 | 可（索引可 DROP），但退回无约束状态＝退回"能不能访问由存储顺序决定" |

⇒ 上线顺序固定为**先迁移、再上新代码**；0006 跑之前先整表备份（手册 5.4 / 5.8）。

## 5. 环境变量登记规则（由门禁强制）

`scripts/check-config.py` 双向核对：

| 方向 | 规则 |
|---|---|
| 双向 | `application.yaml` 里出现的 `${VAR}` 必须能在 `.env.example` 找到同名行；反向亦然（模板里不许有 yaml 不认的键） |
| 特殊形态 | `.env.example` 里**注释掉的键**视为"已登记但默认不设"（例：`MANAGEMENT_SERVER_ADDRESS` 在同端口时设了反而启动失败） |
| 单向 | yaml 的每一个 `app.*` 叶键必须出现在 IDE 元数据 `additional-spring-configuration-metadata.json` |

新增环境变量的完整改动面（少一处就会有"能配但读不到"或"读得到但没人知道"）：
`application.yaml` 默认值 → `.env.example` → 元数据 json → 手册 5.3 表 → README 配置表 → 若影响行为再改手册 2.x/6.x。

## 6. 门禁与脚本台账

### 6.1 `scripts/` 全量

| 脚本 | 作用 | 在 CI | 需要外部资源 |
|---|---|---|---|
| `check-docs.py` | 文档结构门禁（目录↔标题、表格竖线、相对链接与跨文件锚点、遗留标记、手册变更记录连续性） | ✅ `gates` | 否 |
| `check-config.py` | 配置登记门禁（第 5 节） | ✅ `gates` | 否 |
| `smoke.sh` | 分节接口/WS 冒烟，专管单测覆盖不到的拦截器、序列化、限流、握手 | ⚠️ 只做 `bash -n` 语法检查 | **是**：实例 + 库 |
| `db-migrate.sh` | 幂等增量迁移（`--check` 只列清单） | ❌ | 是：库 |
| `backup-mysql.sh` | 每日全量备份 + 保留期清理 | ❌ | 是：库 |
| `gen-dev-env.sh` | 生成本地 `JWT_SECRET` 并列出剩余人工必填项 | ❌ | 否 |
| `check-auth-session.mjs` | 前端会话/静默续期逻辑（`api/auth.ts`） | ✅ `frontend` | 否 |
| `check-notification.mjs` | 通知单例（连发、定时器回收、幂等） | ✅ | 否 |
| `check-knowledgebase-flag.mjs` | 检索三态角标判据 | ✅ | 否 |
| `check-history-record.mjs` | 历史行映射 + 检索状态透传 + 静态防内联 | ✅ | 否 |
| `check-chat-frame.mjs` | WS 回合帧分派（error 解冻、解锁只属于终点、视图必须走统一入口） | ✅ | 否 |
| `check-recording-upload.mjs` | 通话录音收尾结局可见 | ✅ | 否 |
| `check-denial-ledger.mjs` | 拒绝台账分格与三态 + 接线 | ✅ | 否 |
| `check-registries.mjs` | **本文件与代码实况的一致性**（第 1/2/3/4/6 节的清单能否被代码逐项指向）；**另锁一条不由本文件承载的口径**——`knowledgebases` 的写入面只有一处（v2.53 第 6 组，判据来自手册 4.5 第 13 条，落在控制器 / service / 迁移 / `index.sql` / 冒烟五处实况） | ✅ | 否 |

**Node 桩测的共性**：零依赖、不碰网络与库、用 Node 原生类型擦除直接 `import` 生产模块
⇒ 被 import 的模块**只能依赖裸包名或 `import type`**（`utils/websocket.ts` 与 `composables/useWebRTC.ts` 因此不可测）。
每道桩测都包含**静态源码断言**（防"又内联一份副本"）与反向锚点。

### 6.2 覆盖映射（哪个改动由哪道门禁兜）

| 改动面 | 兜它的检查 |
|---|---|
| 后端任何逻辑 | `./mvnw test`（CI `backend`） |
| 前端 `utils/*` 纯模块 | 对应 `check-*.mjs` |
| 前端视图接线（是否走统一入口） | 桩测里的静态断言 |
| 模板类型窄化 | `npm run build`（`type:check` 查不到） |
| 拦截器顺序 / 序列化 / 限流 / 握手 | `smoke.sh`（人工，上线前） |
| 文档、配置登记 | 两个 Python 门禁 |
| 登记表与代码漂移（新增 Kind / 工具 / 迁移 / 开放端点 / 门禁脚本而没在本文件登记） | `check-registries.mjs`（CI `frontend`） |
| 授权依据表 `knowledgebases` 多出第二个写入点（含已删的 `/api/knowledges` 控制器悄悄回来、`index.sql` 与迁移不同口径） | `check-registries.mjs` 第 6 组（CI `frontend`）+ `smoke.sh` §5 两条 404 锚点（人工） |

## 7. 相关文档

- [DEVELOPMENT.md](DEVELOPMENT.md) — 命令与批次流程
- [DEPLOYMENT.md](DEPLOYMENT.md) — 迁移与上线顺序
- [../AGENTS.md](../AGENTS.md) — 为什么"加了字段不等于生效"是本项目的头号缺陷形态
