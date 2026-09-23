# 云谕助手（yunyu-assistant）

> 面向开发与运营人员的「AI 语音助手搭建 + 模型调试」一体化 Web 平台。
> 配置即用、边聊边调、语音与文本双通道、知识可控、全程可审计。

## 功能特性

| 模块 | 能力 |
|---|---|
| 账号认证 | 注册 / 登录 / 修改密码 / 个人资料，JWT 无状态鉴权，登录限流防爆破 |
| 助手管理 | 助手增删改查（软删除 + 属主校验）、人设 / 音色 / 模型参数配置、知识库绑定、人设模板、**可用工具白名单（按助手裁剪能力）** |
| 语音通话 | WebRTC 建链、ASR 实时转写、LLM 流式回复、TTS 播报、VAD 打断（网关侧执行）、沉默追问、LLM 主动挂断、通话记录、**通话录音（本地合成上传与回放）**、弱网自动降级 |
| 文本对话 | 流式对话、人设热更新、知识库动态切换、对话重置、工具调用可视化、Markdown 渲染 |
| 知识库 | RAGFlow 双层后端代理（密钥零下发）、文档上传 / 删除 / 切片解析、多库联合检索、检索效果测试 |
| 记录与统计 | 通话记录列表 / 详情、近 1/7/30 天语音通话用量聚合（口径限制见手册 7.4 S-12） |
| 组织协作 | 组织 / 团队成员管理（owner/editor/viewer 角色矩阵）、组织内资源共享与数据隔离 |
| 用量配额 | 助手上限、单日通话次数 / 时长 / 消息量配额拦截 + 用量账单视图（组织优先 / 用户兜底） |
| 开放 OpenAPI | 第三方应用 API Key（`X-API-Key`）、文本对话流式 SSE（多轮会话续聊）、WebRTC 语音会话、**PSTN 外呼（可插拔网关）**、**Webhook 回调（通话/外呼/消息事件，异步+重试+签名）**，用量计入属主配额 |
| AI 工具 | 挂断（hangup）、当前时间、天气（高德）、联网搜索、**深度研究（检索 + 多篇正文一次抓取汇总）**、**文生图 / 文生视频（异步任务）/ 网页正文读取**，支持 Function Calling 递归续答；**一个工具类即一个能力**，依赖密钥/开关未配置的工具不注册（模型不可见），且可按助手勾选可用工具，见手册 2.8 |
| 工程化 | 操作审计、全局异常脱敏、健康检查、环境变量化配置、逻辑删除、统一响应体 |

## 技术栈

**后端**：Spring Boot 3.5 · Java 21 · MyBatis-Plus 3.5 · MySQL 8 · Spring AI 1.0（OpenAI 兼容）· Spring WebSocket · JJWT · OkHttp

**前端**：Vue 3.5 · TypeScript · Vite 6 · vue-router 4 · TailwindCSS 3 · WebRTC · lucide-vue-next

## 目录结构

```
yunyu-assistant/
├── backend/                 # Spring Boot 后端（单模块）
│   └── src/main/
│       ├── java/com/leyon/backend/   # controller/service/mapper/entity/handler/interceptor/tool
│       └── resources/                # application.yaml、index.sql（建库与种子数据）
├── frontend/                # Vue3 前端（Vite）
│   └── src/                 # api/composables/router/utils/views/components
├── docs/                    # 项目手册（详见《云谕助手项目手册.md》）
├── start-backend.bat        # 一键启动后端（双击）
├── start-frontend.bat       # 一键启动前端（双击）
└── .env.example             # 环境变量模板（复制为 .env 后填写）
```

## 快速开始

### 前置依赖

- JDK 21、Node.js ≥ 20、MySQL 8（需运行中）
- 可选（未配置时对应功能降级；AI 工具类未配置 Key 时该工具直接不注册）：LLM API（OpenAI 兼容）、RAGFlow、RustPBX 语音网关、搜索 / 天气 / 图像 / 视频 API

### 1. 初始化数据库（首次）

执行 `backend/src/main/resources/index.sql`（含建库、建表与种子数据）：

```bash
mysql -uroot -p < backend/src/main/resources/index.sql
```

> 脚本开头含 `DROP DATABASE`，仅限全新环境执行。

### 2. 配置环境变量

复制 `.env.example` 为 `.env` 并填写必填项（`.env` 已被 git 忽略，不会入库）：

- 必需：`DB_USER` / `DB_PASSWORD` / `JWT_SECRET`（≥32 字节强随机值）
- 可选：`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `RAGFLOW_*` / `RUSTPBX_ENDPOINT` / `SEARCH_*` / `WEATHER_API_KEY` 等

> 未配置第三方密钥时后端仍可启动（`/actuator/health` 返回 UP），对应功能运行时降级。

### 3. 启动

**方式 A：一键脚本（Windows，推荐）**

- 双击 `start-backend.bat` → 后端 http://localhost:8080
- 双击 `start-frontend.bat` → 前端 http://localhost:5173

**方式 B：命令行**

```bash
# 后端（backend 目录）
./mvnw -DskipTests spring-boot:run

# 前端（frontend 目录）
npm install
npm run dev
```

### 4. 访问

浏览器打开 http://localhost:5173 → 注册账号 → 创建助手 → 文本对话或语音通话。

## 配置说明

所有敏感配置经环境变量注入（模板见 [.env.example](.env.example)），关键项：

| 变量 | 说明 |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | MySQL 连接（必需） |
| `JWT_SECRET` / `JWT_EXPIRATION` | JWT 签名与有效期（Secret 必需，≥32 字节） |
| `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `AI_MODEL` | LLM（OpenAI 兼容，如 DeepSeek / 通义） |
| `RAGFLOW_API_KEY` / `RAGFLOW_ENDPOINT` | RAGFlow 知识库服务 |
| `RUSTPBX_ENDPOINT` / `RUSTPBX_SILENCE_TIMEOUT` / `RUSTPBX_BREAK_ON_VAD` | 语音网关 |
| `SEARCH_API_KEY` / `SEARCH_ENDPOINT` | 联网搜索工具（二者缺一则 `web_search` 不注册） |
| `WEATHER_API_KEY` | 高德天气工具（未配置则 `get_weather` 不注册） |
| `IMAGE_API_KEY` / `IMAGE_ENDPOINT` / `IMAGE_MODEL` | 文生图工具（OpenAI 兼容 images；未配 Key 则不注册） |
| `VIDEO_API_KEY` / `VIDEO_ENDPOINT` / `VIDEO_MODEL` | 文生视频工具（OpenAI 兼容 videos 异步任务；未配 Key 则不注册） |
| `WEBFETCH_ENABLED` / `WEBFETCH_MAX_BYTES` | 网页正文读取工具（免第三方密钥，但会出网访问模型给出的公网地址，**默认 false**；`deep_research` 同样要求它为 true） |
| `RESEARCH_MAX_SOURCES` | 深度研究默认抓取网页数（默认 3，硬上限 5；模型入参可覆盖） |

## 文档

- 项目手册：[docs/云谕助手项目手册.md](docs/云谕助手项目手册.md)（项目概述 / 功能说明 / 技术架构 / 开发规范 / 部署流程 / 维护指南 / API 与 WS 协议速查）

## 项目现状与迭代方向

**当前完成度**：核心链路（认证 / 助手管理 / 文本对话 / 语音通话 / 知识库 / 记录统计 / AI 工具 / 审计）代码层面均已实现；验证口径需分层理解——REST 与 WS 接口层、归档与外呼等已有本地端到端记录，但**语音通话的 AI 回复主链路（通话记录落库 / `callId` 回传 / 录音上传 / 配额拦截 / 转写落库）截至 v2.25 修复前从未真正跑通，也尚未在真实 RustPBX 网关上做端到端复测**（详见手册 6.6"完成度债"与 7.4 C-15/C-30）；前端已整体重构为纯白极客风格并通过 `npm run build`。P0 工程基建已落地（v2.5）：会话（Session）模型、refresh token + 登出黑名单 + 登录锁定、后端 37 项核心链路单测、前端 ESLint 门禁。P1 产品能力补齐已全部落地（v2.6~v2.9）：助手分页与搜索、Redis 多实例支撑、调试时间线、角色体系与用户管理。P2 规模运营已全部落地（v2.10~v2.14）：长会话消息惰性分页、ICE 可配置化 / 通话录音与回放 / 弱网降级、数据归档与容量治理（v2.13）、多租户与商业化前置（v2.14：组织数据隔离 + 用量配额账单 + 开放 OpenAPI）。v2.15 将开放 OpenAPI 文本对话升级为**多轮会话**；v2.16 开放 **OpenAPI 语音能力**（WebRTC 语音会话端点 `/api/open/ws-voice`）；v2.17 实现 **PSTN 外呼网关（可插拔）+ Webhook 回调**；v2.18 实现 **PSTN 外呼 DIALING 超时自动扫描** 并提供 **TURN（coturn）部署物料**（见 [deploy/turn](deploy/turn/) 与手册 5.9）；v2.19 收尾剩余登记事项（**定时归档跨实例防重**（分布式锁）、**语音配额逐条拦截**、**前端应用管理页** `/apps`）。后端单测 155 个全绿。v2.20~v2.21 完成两轮死代码清理：删除无注入点的 `ASRService`/`TTSService`、前端零引用的三个 composable 与 Pinia store（连同 Pinia 依赖下线）、以及 9 个无消费者的腾讯云 ASR/TTS 凭据配置键。v2.22 完成一致性审计第 1 批修复：`PUT /api/auth/password` 补放行路径令牌自解析（此前必然 500）、语音通话建立时配额不再被吞掉（超限即终止通话）、RAGFlow 数据集列表与删除补对象级归属授权（S-01 收口），后端单测增至 168 个全绿。v2.23 完成一致性审计第 2 批：`WEBHOOK_RETRY_*` 三个环境变量真正可注入、删除 `ModelAdapter.getModelName()` 死接口（连带错位模型键）、组织成员列表批量回填 `username`（不再显示 UUID）、手册目录/测试实况/接口约定章节对齐代码，并把 23 个失效绝对链接改为相对路径，后端单测 169 个全绿。v2.24 完成一致性审计第 3 批（收口）：注册/改密密码规则统一为 ≥6（此前正则放行 5 位、与自身文案和前端均不一致），`/api/auth/refresh` 补 `role` 且回查账号（软删除后不再续期），配置元数据由 12 项补全至 `app.*` 全 38 项，`.env.example` 补入 4 个已文档化却缺失的变量；RustPBX 的 TTS 服务商经论证确定保持网关侧固定（不改码），软删除令牌照常有效与前端未接静默续期两项登记为已知限制，后端单测增至 172 个全绿。v2.25 完成一致性审计第 4 批，修掉两条主链路缺陷并收口五处越权/SSRF：**语音通话的助手 ID 改由握手 URL 路径段解析**（此前只从 `offer` 消息体取值而前端从不发送，导致 AI 每轮回"语音助手未就绪"、通话记录不落库、录音无法回传、通话与消息配额从未拦截——语音侧的"已通过本地验证"此前仅覆盖信令建链与鉴权）；**文本对话成功收尾补发 `query_end`**（携带 `message/costTime/knowledgebase/tokenUsage`，前端气泡流式态与耗时/引用/Token 诊断自此正常）；检索测试接口按可见数据集授权、可指定 path/method 的通用 RAGFlow 转发端点整体删除、对话知识库按可见范围求交、组织 viewer 成员不再可改写共享助手人设、Webhook 出站地址经 `ExternalUrlValidator` 双重校验（禁内网/云元数据，关闭重定向）且 PSTN 回调令牌改常量时间比较。前端本批零源码改动，后端单测增至 **32 类 / 191 例**全绿（语音与 Webhook 的真实网关/外发链路未做在线端到端验证）。v2.26 为**手册↔代码实况对齐批次（纯文档、零行为变更）**：补全 7.1 认证端点清单（`/api/auth/refresh`、`/api/auth/logout` 此前漏列），把滞后的待跟踪项按实况收口（S-16 的唯一索引与逻辑删除其实早已存在，残留仅是软删账号占位导致同名重注册撞 DB 约束；C-23② 所称"归属校验发生在鉴权前"经复核不成立——认证前业务消息一律不响应），并如实登记三处**此前被过度声明的"完成"**：配额管理只有后端 API 而无管理页入口、用量统计实为"近 1/7/30 天个人语音聚合"且不过滤通话状态也不含文本消息、辅助调试能力（人设模板 / 快捷命令 / 消息搜索 / 对话导出）只接在语音工作台。v2.27 为 **AI 工具扩展批次**：助手新增文生图（`generate_image`）、文生视频（`generate_video` 提交 + `query_video` 查询，拆两个工具是因为工具调用在对话流内同步执行、不能在单工具里轮询等出片）、网页正文读取（`fetch_webpage`，免第三方密钥但默认关闭）；架构上收口为"一个工具类即一个能力"——新增类只需暴露 `ToolCallback` Bean 并被自动收集，依赖密钥/开关未配置时用 `@RequiresProperty` 让工具**根本不注册**（此前 `get_weather`/`web_search` 在未配 Key 时仍暴露给模型、只能执行时报错），工具重名由静默覆盖改为启动即失败；前端 `markdown.ts` 补图片渲染，生图结果不再只是裸链接。图像/视频按 OpenAI 兼容契约实现，**尚未持有真实服务商账号做过一次调用**（详见手册 6.6 与 7.4 C-32~C-36）。v2.28 为**能力扩展与按助手裁剪批次**：新增 `deep_research`（把"检索 + 逐篇抓取正文 + 汇总带来源摘要"压缩进**单次工具调用**——每轮工具迭代都要重跑一次完整流式模型调用，语音场景即一轮播报延迟，故不靠模型自己串两次工具；组合既有 `web_search`/`fetch_webpage` 而非复制其网络与安全逻辑，SSRF 校验、关闭重定向、类型与字节上限全部沿用）；工具能力**按助手裁剪**（`assistants.tools` 白名单 + 三条对话链路统一过滤 + `GET /api/tools` 工具字典 + 前端"可用工具"勾选，空配置=全部可用，故既有助手零迁移即行为不变；**工具集在 WS 连接建立时快照，改配置需重进助手或下次通话才生效**）；`@RequiresProperty` 的两套开关表达收敛为内联 `key=value`（判定结果不变）。**已部署库需手工执行一次 `ALTER TABLE assistants ADD COLUMN tools ...`**（手册 5.4 已登记语句与不执行的后果；本批只在本机开发库执行过，其他环境仍需各自执行，服务侧未做接口级端到端验证）。同时手册新增 [6.7 与豆包能力横向对比与差距收口路线](docs/云谕助手项目手册.md#67-与豆包能力横向对比与差距收口路线)：14 行能力矩阵按"持平 / 契约接线 / 架构 / 验证债"四类归因，结论是豆包多数模型能力可采购、本项目的差异化在治理与私有化，**而最贵的差距是"语音主链路从未真机跑通"这条验证债，故下一批先还债不加功能**（豆包侧信息仅来自公开产品页与 App Store 描述，未逐项实操核对）。已知限制（OpenAPI 外呼媒体回流不实现、语音主链路未经真网关端到端验证、统计口径偏差、配额无运营入口等）详见手册 [6.6 已知限制与演进方向](docs/云谕助手项目手册.md#66-已知限制与演进方向) 与 [7.4 问题追踪与修复记录](docs/云谕助手项目手册.md#74-问题追踪与修复记录)。

**迭代路线图**（P0~P2 详见手册 6.6；v2.28 起以 6.7 的横向对比为取舍依据）：

- **P0 工程基建与安全加固**：✅ 会话（Session）模型落地 · ✅ refresh token + 登出黑名单 + 登录失败锁定 · ✅ 单测与 ESLint 质量基建
- **P1 产品能力补齐**：✅ 助手列表分页与搜索 · ✅ Redis 多实例支撑 · ✅ 调试时间线可视化 · ✅ 角色体系与用户管理
- **P2 规模运营与体验优化**：✅ 长会话消息惰性分页 + 数据归档与容量治理 · ✅ 通话录音与回放 + 弱网降级 + ICE 可配置化（TURN 部署待实施；录音所属的语音 AI 主链路待真网关复测） · ✅ 组织数据隔离 + 用量配额账单 + 开放 OpenAPI（配额**配置仅后端 API**，管理页尚无入口）

## 健康检查

- `GET /actuator/health`（含 db / ping / disk 明细）
- `GET /actuator/metrics`、`GET /actuator/info`