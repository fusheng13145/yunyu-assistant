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
`application.yaml` 默认值 → `.env.example` → 元数据 json（**仅 `app.*` 叶键**；`spring.*` 由 Boot 自带元数据，门禁也不查它）→ 手册 5.3 表 → README 配置表 → 若影响行为再改手册 2.x/6.x。

## 6. 门禁与脚本台账

### 6.1 `scripts/` 全量

| 脚本 | 作用 | 在 CI | 需要外部资源 |
|---|---|---|---|
| `check-docs.py` | 文档结构门禁（目录↔标题、表格竖线、相对链接与跨文件锚点、遗留标记、手册变更记录连续性） | ✅ `gates` | 否 |
| `check-config.py` | 配置登记门禁（第 5 节） | ✅ `gates` | 否 |
| `smoke.sh` | 分节接口/WS 冒烟，专管单测覆盖不到的拦截器、序列化、限流、握手；**§7.2 是内部聊天 WS 的帧级失败出口**（v2.69：14 条帧级 + 2 条握手码，真实回合两条由 `SMOKE_WS_CHAT=1` 显式放行；需 Node ≥22 的全局 `WebSocket`，缺项整节具名 SKIP 而不是判绿） | ⚠️ 只做 `bash -n` 语法检查 | **是**：实例 + 库（§7.2 另需 Node ≥22） |
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
| `check-page-layout.mjs` | 「左列表 + 右内容」布局壳的单点（`PageShell` / `NavList` 各一处、六视图走壳而非自声明整屏根、导航与登出没有第二份、每页左右归属明确、新组件不写死颜色也不引新依赖；**弹层遮罩自己就是滚动容器且居中交给卡片 `margin: auto`、视图侧不内联 `fixed inset-0`、六页右栏都有滚动出口**（v2.65 第 6 组） | ✅ `frontend` | 否 |
| `check-registries.mjs` | **本文件与代码实况的一致性**（第 1/2/3/4/6 节的清单能否被代码逐项指向）；**另锁九条不由本文件承载的口径**——`knowledgebases` 的写入面只有一处（v2.53 第 6 组，判据来自手册 4.5 第 13 条，落在控制器 / service / 迁移 / `index.sql` / 冒烟五处实况），模型清单 / 助手级成本参数的判据入口只有一处（v2.54 第 7 组，判据来自手册 4.5 第 14 条），配额四项数值的上界与"0＝关闭"文案各只有一处（v2.57 第 8 组，判据来自手册 4.5 第 15 条），知识库可见性求交的判据只有一处且三条对话通道都经它（v2.58 第 9 组，判据来自手册 2.5 与 4.5 第 13 条），单日通话时长的判据只有一处且发起侧与回合边界共用、通话中不得扣次数（v2.59 第 10 组，判据来自手册 4.5 第 17 条），助手模型的局部更新必须区分三态且清空只能由写侧一处归一（v2.60 第 11 组，判据来自手册 4.5 第 18 条，另读前端两处提交点与 `smoke.sh` §3.6），模型出站地址的合成口径只有一处且流式失败出口必须"error 帧 → 收尾标记 → `query_end`"三帧齐全（v2.62 第 12 组，判据来自手册 4.5 第 19/20 条，另读 `.env.example` / README / 手册 5.3 四处口径与两条 WS 单测用例名），门禁自身的退出码与 `smoke.sh` 的判决边界必须由判据守着（v2.70 第 13 组，glob 取靶逐字锁 `process.exit(failures === 0 ? 0 : 1)` 收尾，并锁"有 FAIL 即 exit 1"的门与 §7.2 两条具名 SKIP 边界，判据来自手册 4.5 第 27 条），模型流的超时判据只有一处、且"用户已走"必须撤掉在途订阅并把已生成部分落库（v2.71 第 14 组，判据来自手册 4.5 第 28 条，另读 `.env.example` / IDE 元数据 / README / 手册 5.3 四处配置口径与适配器、`ChatService` 两侧用例名，并反向锁三条"判据只到 Reactor 订阅层"的边界互指 S-26）；这九组读的都是源码实况（第 6/7/8/11/13 组另读迁移与 `smoke.sh`）而不读本表。**全十四组 218 项以 `process.exit(failures === 0 ? 0 : 1)` 收尾**（v2.69 · C-137：补这一行之前，本道门禁打印"失败 N 项"却 `exit 0`，CI 的 `frontend` job 对它全体免疫；其余八道 Node 桩测一直都有这一行） | ✅ | 否 |

**Node 桩测的共性**：零依赖、不碰网络与库、用 Node 原生类型擦除直接 `import` 生产模块
⇒ 被 import 的模块**只能依赖裸包名或 `import type`**（`utils/websocket.ts` 与 `composables/useWebRTC.ts` 因此不可测）。
每道桩测都包含**静态源码断言**（防"又内联一份副本"）与反向锚点。

### 6.2 覆盖映射（哪个改动由哪道门禁兜）

| 改动面 | 兜它的检查 |
|---|---|
| 后端任何逻辑 | `./mvnw test`（CI `backend`） |
| 前端 `utils/*` 纯模块 | 对应 `check-*.mjs` |
| 前端视图接线（是否走统一入口） | 桩测里的静态断言 |
| 页面形状（整屏根回来、第二份导航或登出、左列表槽丢失） | `check-page-layout.mjs`（CI `frontend`）+ 浏览器逐页走查（人工） |
| 模板类型窄化 | `npm run build`（`type:check` 查不到） |
| 拦截器顺序 / 序列化 / 限流 / 握手 | `smoke.sh`（人工，上线前） |
| 文档、配置登记 | 两个 Python 门禁 |
| 登记表与代码漂移（新增 Kind / 工具 / 迁移 / 开放端点 / 门禁脚本而没在本文件登记） | `check-registries.mjs`（CI `frontend`） |
| 授权依据表 `knowledgebases` 多出第二个写入点（含已删的 `/api/knowledges` 控制器悄悄回来、`index.sql` 与迁移不同口径） | `check-registries.mjs` 第 6 组（CI `frontend`）+ `smoke.sh` §5 两条 404 锚点（人工） |
| 助手级成本参数出现第二份模型清单、或装配点绕过钳制直读实体（界面选得到却不被执行 / 越界值静默生效） | `check-registries.mjs` 第 7 组（CI `frontend`）+ `smoke.sh` §3.5 写侧拒绝与反向锚点（人工） |
| 配额数值出现第二份上界或第二处关闭文案（含前端复制上界、校验被挪到写库之后） | `check-registries.mjs` 第 8 组（CI `frontend`）+ `smoke.sh` §8.1 越界拒绝、反向锚点与"库内值逐字段不变"（人工） |
| 某条对话通道不再按可见范围收敛知识库（出现第二份求交实现、私有包装回来、或新装配点直接把 `knowledge_ids` 交给 ChatService） | `check-registries.mjs` 第 9 组（CI `frontend`）+ 三条通道各自的求交单测 |
| 单日通话时长不再逐轮复核（回合边界丢掉"本通已活秒数"、改用发起侧判据逐轮扣次数、被拒回合只发帧不挂断、或顺手给语音/PSTN 加半套墙钟） | `check-registries.mjs` 第 10 组（CI `frontend`，含三条反向锚点）+ `QuotaServiceTest` 的 `ongoingCallSec_*` 六例与处理器那条用例（**无真机冒烟**：本机无语音网关） |
| 助手模型的局部更新不再区分三态（后端把"空白"折回 null ⇒ 界面上选"默认模型"点了没反应；后端把"没带这一列"也归一成清空 ⇒ 保存人设/音色顺手毁掉模型配置；前端任一提交点退回折空值；其余四处 PUT 带上该列） | `check-registries.mjs` 第 11 组（CI `frontend`，含两条"缺字段不许动列"的反向用例与"其余三列不许顺手扩"判据）+ `smoke.sh` §3.6 六条真机锚点（空串真的清空、只改名不清空、空串与缺失可辨） |
| 实体新增凭据列没表态（字段名命中凭据词表却能被 Jackson 序列化带出、或 `toString()` 打印凭据），或一次性明文改由"整颗实体当响应载荷"交付 | `EntityCredentialSuppressionTest`（随 `./mvnw test` 与 CI `backend` 跑；**刻意不是第 10 道 Node 桩测**——受检对象是编译后的字节码行为，源码正则证不出"输出里没这个键"）+ `smoke.sh` §7.10 三条成对断言（人工，上线前） |
| 内部聊天 WS 的失败出口退化（该拒的没拒、错误正文跑到帧顶层而不是 `data` 下、越权没挂断、`ping` 无 `pong`、流式失败缺 `error` 帧） | `smoke.sh` §7.2 十六条帧级与握手码真机读数（人工，上线前；需 Node ≥22，缺项整节具名 SKIP）+ `check-registries.mjs` 第 12 组的"三帧齐全且顺序固定"（CI `frontend`）+ `ChatWebSocketHandlerTest` 两条具名用例。**关闭码不参与判定**（除越权 1003 外，失败出口与正常收尾同为 1000，读数分不开） |
| 门禁脚本自己失效（打印红却 `exit 0`，CI 收下不判；新增 `check-*.mjs` 没接退出码） | `check-registries.mjs` 第 13 组（CI `frontend`，v2.70 · C-138：逐字锁每道 Node 门禁的 `process.exit(failures === 0 ? 0 : 1)` 收尾，缺行与退化为 `exit 0` 都红；同组锁 `smoke.sh` 的"有 FAIL 即 exit 1"门与 §7.2 两条具名 SKIP 边界）。**边界**：锁行文不保证受检门禁把计数数得真，python 门禁不归它管（S-23，未修） |
| 一次模型调用收不了场（上游不回字节 ⇒ 本轮既不成功也不失败、打字态与 WS 订阅一起悬着；用户已走 ⇒ 内层订阅被丢弃，这一轮既不落库也无人消费；超时出现第二份判据 ⇒ 三条通道各有结论） | `check-registries.mjs` 第 14 组（CI `frontend`，v2.71 · C-139/C-140：超时唯一落点 + `modelAdapter.stream(` 全仓恰一处 + 取消句柄形状 + 四处配置与两处文档口径一致 + 三条边界互指）+ `OpenAiModelAdapterTest` 2 例与 `ChatServiceTest` 新增 2 例；**在途 HTTP 交换不中止是登记边界而非判据（S-26）** |

## 7. 相关文档

- [DEVELOPMENT.md](DEVELOPMENT.md) — 命令与批次流程
- [DEPLOYMENT.md](DEPLOYMENT.md) — 迁移与上线顺序
- [../AGENTS.md](../AGENTS.md) — 为什么"加了字段不等于生效"是本项目的头号缺陷形态
