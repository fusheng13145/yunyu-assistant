# 架构与目录（ARCHITECTURE）

> 结构导读。**权威的目录树与逐包文件清单在[手册 1.5](云谕助手项目手册.md#15-代码库目录结构)，
> 数据模型的逐表说明在[手册 3.5](云谕助手项目手册.md#35-数据模型)**——本文只解释"为什么这么分"和"东西该放哪"，
> 不重复文件计数与逐表字段。

## 1. 总体形态

单体后端 + 单页前端 + 外部服务，全部经同一域名的反向代理：

```
浏览器 ──HTTPS/WSS──> 反向代理(nginx) ─┬─ /            前端静态产物 dist
                                       └─ /api, /ws…   Spring Boot 单体 (:8080)
                                                        ├─ MySQL（唯一事实存储）
                                                        ├─ Redis（可选：多实例共享状态）
                                                        ├─ LLM（OpenAI 兼容，Spring AI）
                                                        ├─ RAGFlow（知识库检索，密钥只在后端）
                                                        └─ RustPBX（语音网关：ASR/TTS/媒体）
```

刻意保持单体的理由：这是一个"治理能力优先"的小规模产品，拆开服务只会把归属校验、配额判定、审计这三条
必须在**同一处**收口的逻辑推到跨进程边界上——历史上这几处的缺陷全部来自"判定分散在多个地方"。

## 2. 后端分层（`com.leyon.backend`）

```
controller/ ─┐
handler/     ├─→ service/ ─→ mapper/ ─→ MySQL     （业务数据）
interceptor/ ┘        ↑
aspect/  task/  tool/ ─┘                           （切面 / 定时回收 / AI 工具）
```

| 包 | 职责 | 边界（不该做的事） |
|---|---|---|
| `annotation` + `aspect` | `@Audit` 声明 + `AuditAspect` 落审计行 | 业务代码不手写审计 SQL；成败判据只在切面一处 |
| `common` | `ApiResponse<T>` 统一信封、两个业务异常 | 不引入 DTO 层（见第 6 节） |
| `config` | 装配（`AppConfig` 拦截器与限流路径注册、CORS、WS）+ **启动期断言守卫** | 守卫只做"配置矛盾即拒绝启动"，不做兜底修正 |
| `controller` | 参数收口、归属校验、响应封装 | 不写业务逻辑、不直接调第三方 |
| `entity` | 实体 + MyBatis-Plus 注解（逻辑删除、自动填充、UUID 主键）+ 列↔结构的转换（如 JSON 字符串列折成对象） | 凭据外发抑制也落在实体（见 COMPONENT-GUIDELINES 第 5 节） |
| `handler` | 两个 WS 业务处理器 + 全局异常处理 + MP 自动填充 | 异常处理器只映射"异常类型 → HTTP 状态"，不改业务语义 |
| `interceptor` | 三条鉴权通道（内部 JWT / 管理端 / 开放 API Key）+ 限流 | 判定顺序固定：限流 → 验凭据 → 验能力 |
| `mapper` | 数据访问 | 复杂 SQL 用注解 SQL 而非 lambda 包装器（见测试手法约束） |
| `service` | 业务编排、配额判定、第三方调用封装（超时 + 降级） | 单点收口项见第 5 节 |
| `task` | 四个定时任务（归档 / 外呼超时 / Webhook 重试 / 未认证连接回收） | 共用 Spring 默认调度线程，长任务会顺延其他任务 |
| `tool` | AI 工具（一类即一能力）+ 注册表 + 启用门控 | 未配置依赖的工具**不注册**，而不是注册后报错 |
| `util` | 无状态判据：JWT、来源地址、出站地址校验、Key 摘要 | 判据必须可单测，不注入业务依赖 |

分层约束的原文表述见[手册 3.2](云谕助手项目手册.md#32-后端分层) 与
[手册 4.2 代码分层与职责](云谕助手项目手册.md#42-代码分层与职责)。

## 3. 前端结构（`frontend/src`）

| 目录 | 现状 | 口径 |
|---|---|---|
| `views/` | 10 个页面 | 页面级组装 + 局部状态；会话消息流是视图内 `ref` |
| `components/` | 6 个共享组件 | "第三次出现才抽"——见 [DESIGN.md](../DESIGN.md) 控件规范；v2.63 起 `PageShell.vue`（左列表 + 右内容的布局壳）与 `NavList.vue`（站点导航）在此，**整屏根、页头、主题开关、登出只在壳里一次** |
| `composables/` | 6 个 | 有生命周期/全局状态的逻辑；通知是模块级单例 |
| `utils/` | 8 个 | 除 `websocket.ts`（运行时依赖 `api/auth`）外都是**可被 Node 桩测直接 import 的纯模块**：运行时相对 import 为零 |
| `api/` | 9 个 | 原生 `fetch` 封装 + 统一注入 `Authorization` + 解析 `ApiResponse`；静默续期在 `auth.ts` 单点 |
| `router/` | 1 个 | 唯一站点导航在 `components/NavList.vue`（由 `PageShell` 挂载，v2.63）；路由守卫见 [PAGE-STRUCTURE.md](PAGE-STRUCTURE.md) |
| `types/` | 1 个 | 前后端契约类型；**视图内不得再写内联类型副本**（曾因此挡住字段透传） |
| `style.css` | 全站令牌 | 见 [DESIGN.md](../DESIGN.md) |

**没有独立状态库**（Pinia 已随零引用代码一起下线）、**没有 axios**、**没有组件级测试框架**。
这不是遗漏而是刻意的最小依赖：可验证性由"纯模块 + Node 桩测 + 构建期类型检查"承担。

## 4. 通信设计

| 通道 | 地址 | 凭据 | 说明 |
|---|---|---|---|
| 内部 REST | `/api/**` | `Authorization: Bearer <access>` | 统一信封 `{code,message,data}`；非 200 由前端抛错展示 |
| 内部 WS | `/ws/{assistantId}`、`/ws-voice/{assistantId}` | 允许先握手，首条 `auth{token}` 帧认证 | 因此免认证连接必须有存活上限（`task/UnauthenticatedSocketReaper`） |
| 开放 REST | `/api/open/**` | `X-API-Key` | 按端点→能力表判定，未登记端点 fail-closed |
| 开放 WS | `/api/open/ws-voice/**` | 握手 `X-API-Key` 头（URL 查询参数通道默认关闸） | 顺序＝限流 → 验 Key → 验能力 |
| 网关回调 | `/api/open/callbacks/**` | `X-Gateway-Token` | 从开放拦截器排除，独立校验 |

三条凭据路径一律只接受 `type=access` 的令牌，且都要过"凭据版本戳"（改密即作废旧令牌）。
帧级协议见[手册 7.2 WebSocket 消息参考](云谕助手项目手册.md#72-websocket-消息参考)。

## 5. 数据组织

全部表按职责分组（逐表字段与索引见手册 3.5）：

| 组 | 表 | 组织口径 |
|---|---|---|
| 身份与准入 | `users` `invite_code` | 密码只存 BCrypt；邀请码是一次性凭据，**不做软删**；`users.email` / `users.phone` 自 v2.89 起是**登录凭据**，其"仅活账号参与唯一"由 VIRTUAL 生成列（`email_active` / `phone_active`）+ 唯一索引表达，不在服务层用布尔标志模拟 |
| 配置 | `assistants` `knowledgebases` | JSON 字符串列承载列表（`knowledge_ids` / `tools`），实体内折算成对象 |
| 会话内容 | `sessions` `records` | `records.role` 是数值枚举；检索状态折成 `knowledgebase` 对象对外 |
| 语音 | `call_records` `outbound_calls` | 通话状态数值枚举 / 外呼状态字符串枚举；录音只存文件名，落在本地文件系统 |
| 治理 | `quotas` `quota_daily_usage` `audit_logs` `orgs` `org_members` | **判定账本与展示口径分离**：配额扣减只由带余额条件的原子 UPDATE 推进 |
| 开放 | `api_apps` `webhook_deliveries` | Key 只存 SHA-256 摘要，明文只在创建响应里出现一次 |
| 运维 | `records_archive` `call_records_archive` `audit_logs_archive` `schema_migrations` | 归档表与源表同构、保留原 id、业务查询不读 |

主键统一 `VARCHAR(36)` UUID；业务表统一 `is_deleted` 逻辑删除（归档表与一次性凭据例外）。

## 6. 三条贯穿全仓的设计决定

**① 无 DTO 层。** 实体直接对外序列化，代价是实体形状即 API 形状，因此**外发抑制必须落在实体上**
（凭据列用注解封掉、JSON 字符串列用 `@JsonIgnore` + 派生 getter 折成对象），而不是在每个出口手写脱敏——
后者已被证明会漏、且会改写查出来的行。收益是少一层映射，风险由"实体级测试 + 冒烟判据"兜住。

**② 判定单点化。** 凡是"同一个问题被问两处就会给两个答案"的逻辑，一律抽成单点，本项目已有的：

| 判据 | 单点 |
|---|---|
| 客户端地址 | `util/ClientIpResolver`（限流桶键 / 登录锁定 / 审计 IP 同源） |
| 登录标识的形状与归一化 | `service/IdentifierPolicy`（v2.89：用户名/邮箱/手机号的判态、邮箱转小写、手机号剥分隔符、写侧格式拒绝四处都只在这一处判；**读侧宽松、写侧严格是同一单点的两条口径**，前端与控制器都不再各写一份，否则同一串标识在登录与注册会得到两个答案） |
| 令牌是否可用 | `JwtUtil.validateAccessToken()` + `AccountCredentialService`（版本戳判定在这两处内部，不在 7 个调用点） |
| 限流计数 | `service/RateLimitService` |
| 开放侧拒绝计数 | `service/OpenApiDenialMeter` |
| 对话记录落库 | `service/ConversationRecordWriter`（v2.66：三通道共用一处写入与失败计数，不在各连接收尾处各写一份） |
| 用量统计口径 | `service/UsageStatsService`（v2.67：结算判定 / 消息数来源 / 归档并读 / 组织作用域四处都只在这一处判，mapper 的 SQL 只取行） |
| 审计成败 | `aspect/AuditAspect` |
| WS 回合帧分派 | `frontend/src/utils/chatFrame.ts` |
| 历史行映射 | `frontend/src/utils/mapHistoryRecord.ts` |
| 通知实现 | `frontend/src/composables/useNotification.ts` + `App.vue` 单挂载点 |

新增判据时应沿用这条：**放在能一次覆盖所有调用链的位置，而不是逐处补。**

**③ 迁移是账本不是脚本。** 建库脚本只用于全新环境（且含 `DROP DATABASE`，禁止整份灌已有库）；
已部署库的列变更一律走 `db/migrations/` + `scripts/db-migrate.sh`，由 `schema_migrations` 记账。
逐条迁移的内容与可回退性见 [REGISTRY.md](REGISTRY.md#4-数据库迁移账本)。

## 7. 扩展点

| 想加 | 加在哪 | 必须同时做 |
|---|---|---|
| AI 工具 | `tool/` 一个 `@Component` 类 + 一个 `ToolCallback` Bean | 依赖外部 Key 就标 `@RequiresProperty`；名字撞车会启动失败 |
| 开放端点 | `interceptor/OpenApiAuthInterceptor.REQUIRED_SCOPES` | **不登记即 403**，所以新增端点必须同时登记所需能力 |
| 业务表/列 | `db/migrations/00NN_*.sql`（幂等） | 实体列变更与迁移同批次；不可回退项（删列）要在文档写清上线顺序 |
| 管理端点 | `controller/AdminController` | 归属/角色校验 + 审计注解 + 数值维度不得留 NULL |
| 前端页面 | `views/` + `router/` | 守卫 meta、导航入口（只有一处侧栏）、`api/` 模块、失败可见 |

## 8. 相关文档

- [COMPONENT-GUIDELINES.md](COMPONENT-GUIDELINES.md) — 写代码时的具体约束与测试手法
- [DEVELOPMENT.md](DEVELOPMENT.md) — 跑门禁与批次流程
- [REGISTRY.md](REGISTRY.md) — 六张登记表：改到某一类东西时必登的账
- [DEPLOYMENT.md](DEPLOYMENT.md) — 部署形态与不可逆顺序
