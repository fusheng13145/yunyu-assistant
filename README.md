# 云谕助手（yunyu-assistant）

> 面向开发与运营人员的「AI 语音助手搭建 + 模型调试」一体化 Web 平台。
> 配置即用、边聊边调、语音与文本双通道、知识可控、全程可审计。

## 功能特性

| 模块 | 能力 |
|---|---|
| 账号认证 | 注册 / 登录 / 修改密码 / 个人资料，JWT 无状态鉴权 + 双令牌**静默续期**（临期或 401 时单飞刷新后重放，跨标签页互不踢出，v2.30），登录限流防爆破；**会话凭据只认 access 令牌**——REST、WS 握手与首条 `auth` 帧统一校验 `type == access`，7 天寿命的 refresh 令牌不能当会话凭据使用（v2.32 C-63） |
| 助手管理 | 助手增删改查（软删除 + 属主校验）、人设 / 音色 / 模型参数配置、知识库绑定、人设模板、**可用工具白名单（按助手裁剪能力）** |
| 语音通话 | WebRTC 建链、ASR 实时转写、LLM 流式回复、TTS 播报、VAD 打断（网关侧执行）、沉默追问、LLM 主动挂断、通话记录、**通话录音（本地合成上传与回放）**、弱网自动降级 |
| 文本对话 | 流式对话、人设热更新、知识库动态切换、对话重置、工具调用可视化、Markdown 渲染 |
| 知识库 | RAGFlow 双层后端代理（密钥零下发）、文档上传 / 删除 / 切片解析、多库联合检索、检索效果测试 |
| 记录与统计 | 通话记录列表 / 详情、近 1/7/30 天语音通话用量聚合（口径限制见手册 7.4 S-12） |
| 组织协作 | 组织 / 团队成员管理（owner/editor/viewer 角色矩阵）、组织内资源共享与数据隔离 |
| 用量配额 | 助手上限、单日通话次数 / 时长 / 消息量配额拦截 + 用量账单视图（组织优先 / 用户兜底 / 环境变量再兜底）+ **管理端配额配置页**；单条用户输入长度上限 2000 字（v2.29，防按条计量的配额被超长消息打穿成本） |
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
│       └── resources/                # application.yaml、index.sql（仅新环境建表）、
│                                     # db/migrations（幂等增量迁移）、db/seed-demo.sql（演示数据，生产禁跑）
├── frontend/                # Vue3 前端（Vite）
│   └── src/                 # api/composables/router/utils/views/components
├── scripts/                 # db-migrate.sh（增量迁移 + --check）/ backup-mysql.sh（备份 + 保留期清理）/ smoke.sh（对已运行实例的接口与 WS 冒烟，v2.30）
├── deploy/turn/             # TURN(coturn) 部署物料
├── docs/                    # 项目手册（详见《云谕助手项目手册.md》）
├── start-backend.bat        # 一键启动后端（双击，内置开发默认值，不读 .env）
├── start-frontend.bat       # 一键启动前端（双击）
└── .env.example             # 环境变量模板（需显式导出，见「配置说明」）
```

## 快速开始

### 前置依赖

- JDK 21、Node.js ≥ 20、MySQL 8（需运行中）
- **必需**：LLM API（OpenAI 兼容 endpoint 与 Key）——v2.29 起 `OPENAI_API_KEY` 未注入则后端**启动即失败**，不再静默降级
- 可选（未配置时对应功能降级；AI 工具类未配置 Key 时该工具直接不注册）：RAGFlow、RustPBX 语音网关、搜索 / 天气 / 图像 / 视频 API

### 1. 初始化数据库（首次）

v2.29 起脚本按用途拆成三份（详见手册 5.4）：

```bash
# ① 全新环境建表（开头含 DROP DATABASE，只在第一次建库时执行）
mysql --default-character-set=utf8mb4 -uroot -p < backend/src/main/resources/index.sql
# ② 登记/应用增量迁移（已部署库每次上线前跑；--check 只看不动）
DB_USER=... DB_PASSWORD=... scripts/db-migrate.sh
# ③（仅本地演示）灌入演示账号与示例对话，生产禁跑
mysql --default-character-set=utf8mb4 -uroot -p yunyu_assistant < backend/src/main/resources/db/seed-demo.sql
```

> ⚠️ 任何时候都不要把 `index.sql`（或其片段）灌向**有数据**的实例：它的第一动作是删库。
> 不跑 `db-migrate.sh` 的后果：实体已含新列 ⇒ 助手查询整体报 `Unknown column 'tools'`。

### 2. 配置环境变量

复制 `.env.example` 为 `.env` 并填写必填项（`.env` 已被 git 忽略，不会入库）：

- 必需：`DB_USER` / `DB_PASSWORD` / `JWT_SECRET`（≥32 字节强随机值）/ `OPENAI_API_KEY`
- 可选：`OPENAI_BASE_URL` / `RAGFLOW_*` / `RUSTPBX_ENDPOINT` / `SEARCH_*` / `WEATHER_API_KEY` / `HEALTH_SHOW_DETAILS` / `MAPPER_LOG_LEVEL` / `LOG_FILE` / `SERVER_ADDRESS` / `MANAGEMENT_SERVER_PORT` / `MANAGEMENT_SERVER_ADDRESS` / `CORS_ALLOWED_ORIGINS` 等（公网部署相关四项见手册 5.3 与 5.10）

> **`.env` 不会被自动读取**（项目无 dotenv 依赖，Spring / JVM / Maven 都不解析它），需显式导出：
> `set -a && . ./.env && set +a && java -jar ...`，或 systemd 的 `EnvironmentFile=`。`start-backend.bat` 也不读 `.env`，它只在变量缺失时给一组开发默认值。
> 未配置可选第三方服务时后端仍可启动，对应功能运行时降级。

### 3. 启动

**方式 A：一键脚本（Windows，推荐）**

- 双击 `start-backend.bat` → 后端 http://localhost:8080
- 双击 `start-frontend.bat` → 前端 http://localhost:5173

**方式 B：命令行**

```bash
# 后端（backend 目录；先导出配置）
set -a && . ../.env && set +a
./mvnw -DskipTests spring-boot:run

# 前端（frontend 目录）
npm install
npm run dev
# 后端不在 8080（例如本机自验证跑在 8091/9091）时，代理目标由这一项决定：
# VITE_PROXY_TARGET=http://localhost:8091 npm run dev
```

### 4. 访问

浏览器打开 http://localhost:5173 → 注册账号 → 创建助手 → 文本对话或语音通话。

## 配置说明

所有敏感配置经环境变量注入（模板见 [.env.example](.env.example)），关键项：

| 变量 | 说明 |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | MySQL 连接（`DB_USER` / `DB_PASSWORD` 必需，无默认值） |
| `JWT_SECRET` / `JWT_EXPIRATION` | JWT 签名与有效期（Secret 必需，≥32 字节；无默认 ⇒ 缺失即启动失败） |
| `OPENAI_API_KEY` | LLM Key（**必需，v2.29 起无占位默认**：缺失或空串即启动失败） |
| `OPENAI_BASE_URL` / `AI_MODEL` / `AI_TEMPERATURE` | LLM endpoint 与默认模型（endpoint 默认 OpenAI 官方地址、模型默认 `deepseek-chat`，**两者不同源**：换服务商时要一起改） |
| `RAGFLOW_API_KEY` / `RAGFLOW_ENDPOINT` | RAGFlow 知识库服务（可选，未配置时知识库调用报错） |
| `RUSTPBX_ENDPOINT` / `RUSTPBX_SILENCE_TIMEOUT` / `RUSTPBX_BREAK_ON_VAD` | 语音网关（可选，未配置时语音通话建立失败） |
| `SEARCH_API_KEY` / `SEARCH_ENDPOINT` | 联网搜索工具（二者缺一则 `web_search` 不注册） |
| `WEATHER_API_KEY` | 高德天气工具（未配置则 `get_weather` 不注册） |
| `IMAGE_API_KEY` / `IMAGE_ENDPOINT` / `IMAGE_MODEL` | 文生图工具（OpenAI 兼容 images；未配 Key 则不注册） |
| `VIDEO_API_KEY` / `VIDEO_ENDPOINT` / `VIDEO_MODEL` | 文生视频工具（OpenAI 兼容 videos 异步任务；未配 Key 则不注册） |
| `WEBFETCH_ENABLED` / `WEBFETCH_MAX_BYTES` | 网页正文读取工具（免第三方密钥，但会出网访问模型给出的公网地址，**默认 false**；`deep_research` 同样要求它为 true） |
| `RESEARCH_MAX_SOURCES` | 深度研究默认抓取网页数（默认 3，硬上限 5；模型入参可覆盖） |
| `HEALTH_SHOW_DETAILS` | **（v2.29）** `/actuator/health` 是否回组件明细，默认 `never`（`/actuator` 免鉴权，公网实例必须保持关闭并由网络层封住，见手册 6.1） |
| `MAPPER_LOG_LEVEL` | **（v2.29）** SQL 日志级别，默认 `INFO`（不打 SQL）；`DEBUG` 才输出语句与参数，本地脚本已设为 `DEBUG` |
| `LOG_FILE` | **（v2.29）** 非空则日志同时落该文件（按天 + 10MB 轮转、保留 7 天）；为空仅控制台 |
| `SERVER_PORT` / `SERVER_ADDRESS` | **（v2.30 新增 `SERVER_ADDRESS`）** 后端端口与监听地址，默认 `8080` / `0.0.0.0`（Boot 原行为）。单机反代部署应设 `127.0.0.1`，让"业务端口不出机器"由内核保证而非只靠云安全组（手册 5.7⑤、5.10②） |
| `MANAGEMENT_SERVER_PORT` / `MANAGEMENT_SERVER_ADDRESS` | **（v2.30 引入，v2.31 真实首跑修正）** actuator 端口与监听地址，默认"与业务同端口、地址**不设**"。⚠️ Boot 3.5.15 两个方向不对称：同端口时给了 address 会直接抛异常起不来（故 yaml 刻意不给默认值、`.env.example` 默认注释），而端口独立时它又**不继承** `server.address`（不设即 `/actuator` 绑全网卡）。结论＝换端口必须两项成对配，且只换端口不设地址会被 `ManagementAddressGuard` 拒绝启动（手册 5.3、6.1 与 7.4 C-59/C-61） |
| `CORS_ALLOWED_ORIGINS` | **（v2.30）** 前端来源白名单（逗号分隔），HTTP 跨域与 WebSocket 握手共用，默认为此前硬编码的 4 个本地端口。**公网站点必须把正式域名加进来**：浏览器 WS 握手一定带 `Origin`，漏配的表现是"能登录、点什么都没反应"且后端日志无异常栈（手册 5.7⑥、6.5） |

| `WS_UNAUTHENTICATED_IDLE_MS` | **（v2.32 新增）** 未认证 WebSocket 连接的存活上限，默认 30000。`/ws/*` 与 `/ws-voice/*` 允许免令牌握手后用首条 `auth` 帧认证，故该上限必须由应用层回收器执行（Tomcat 不回收服务端会话，`setMaxIdleTimeout()` 在服务端是空操作）；回收器 10 秒扫一次，实际关闭时间＝该值向上取整到 10 秒格。详见手册 3.4 |

## 文档

- 项目手册：[docs/云谕助手项目手册.md](docs/云谕助手项目手册.md)（项目概述 / 功能说明 / 技术架构 / 开发规范 / 部署流程 / 维护指南 / API 与 WS 协议速查）

## 项目现状与迭代方向

**当前完成度**：核心链路（认证 / 助手管理 / 文本对话 / 语音通话 / 知识库 / 记录统计 / AI 工具 / 审计）代码层面均已实现；验证口径需分层理解——REST 与 WS 接口层、归档与外呼等已有本地端到端记录，但**语音通话的 AI 回复主链路（通话记录落库 / `callId` 回传 / 录音上传 / 配额拦截 / 转写落库）截至 v2.25 修复前从未真正跑通，也尚未在真实 RustPBX 网关上做端到端复测**（详见手册 6.6"完成度债"与 7.4 C-15/C-30）；前端已整体重构为纯白极客风格并通过 `npm run build`。P0 工程基建已落地（v2.5）：会话（Session）模型、refresh token + 登出黑名单 + 登录锁定、后端 37 项核心链路单测、前端 ESLint 门禁。P1 产品能力补齐已全部落地（v2.6~v2.9）：助手分页与搜索、Redis 多实例支撑、调试时间线、角色体系与用户管理。P2 规模运营已全部落地（v2.10~v2.14）：长会话消息惰性分页、ICE 可配置化 / 通话录音与回放 / 弱网降级、数据归档与容量治理（v2.13）、多租户与商业化前置（v2.14：组织数据隔离 + 用量配额账单 + 开放 OpenAPI）。v2.15 将开放 OpenAPI 文本对话升级为**多轮会话**；v2.16 开放 **OpenAPI 语音能力**（WebRTC 语音会话端点 `/api/open/ws-voice`）；v2.17 实现 **PSTN 外呼网关（可插拔）+ Webhook 回调**；v2.18 实现 **PSTN 外呼 DIALING 超时自动扫描** 并提供 **TURN（coturn）部署物料**（见 [deploy/turn](deploy/turn/) 与手册 5.9）；v2.19 收尾剩余登记事项（**定时归档跨实例防重**（分布式锁）、**语音配额逐条拦截**、**前端应用管理页** `/apps`）。后端单测 155 个全绿。v2.20~v2.21 完成两轮死代码清理：删除无注入点的 `ASRService`/`TTSService`、前端零引用的三个 composable 与 Pinia store（连同 Pinia 依赖下线）、以及 9 个无消费者的腾讯云 ASR/TTS 凭据配置键。v2.22 完成一致性审计第 1 批修复：`PUT /api/auth/password` 补放行路径令牌自解析（此前必然 500）、语音通话建立时配额不再被吞掉（超限即终止通话）、RAGFlow 数据集列表与删除补对象级归属授权（S-01 收口），后端单测增至 168 个全绿。v2.23 完成一致性审计第 2 批：`WEBHOOK_RETRY_*` 三个环境变量真正可注入、删除 `ModelAdapter.getModelName()` 死接口（连带错位模型键）、组织成员列表批量回填 `username`（不再显示 UUID）、手册目录/测试实况/接口约定章节对齐代码，并把 23 个失效绝对链接改为相对路径，后端单测 169 个全绿。v2.24 完成一致性审计第 3 批（收口）：注册/改密密码规则统一为 ≥6（此前正则放行 5 位、与自身文案和前端均不一致），`/api/auth/refresh` 补 `role` 且回查账号（软删除后不再续期），配置元数据由 12 项补全至 `app.*` 全 38 项，`.env.example` 补入 4 个已文档化却缺失的变量；RustPBX 的 TTS 服务商经论证确定保持网关侧固定（不改码），软删除令牌照常有效与前端未接静默续期两项登记为已知限制，后端单测增至 172 个全绿。v2.25 完成一致性审计第 4 批，修掉两条主链路缺陷并收口五处越权/SSRF：**语音通话的助手 ID 改由握手 URL 路径段解析**（此前只从 `offer` 消息体取值而前端从不发送，导致 AI 每轮回"语音助手未就绪"、通话记录不落库、录音无法回传、通话与消息配额从未拦截——语音侧的"已通过本地验证"此前仅覆盖信令建链与鉴权）；**文本对话成功收尾补发 `query_end`**（携带 `message/costTime/knowledgebase/tokenUsage`，前端气泡流式态与耗时/引用/Token 诊断自此正常）；检索测试接口按可见数据集授权、可指定 path/method 的通用 RAGFlow 转发端点整体删除、对话知识库按可见范围求交、组织 viewer 成员不再可改写共享助手人设、Webhook 出站地址经 `ExternalUrlValidator` 双重校验（禁内网/云元数据，关闭重定向）且 PSTN 回调令牌改常量时间比较。前端本批零源码改动，后端单测增至 **32 类 / 191 例**全绿（语音与 Webhook 的真实网关/外发链路未做在线端到端验证）。v2.26 为**手册↔代码实况对齐批次（纯文档、零行为变更）**：补全 7.1 认证端点清单（`/api/auth/refresh`、`/api/auth/logout` 此前漏列），把滞后的待跟踪项按实况收口（S-16 的唯一索引与逻辑删除其实早已存在，残留仅是软删账号占位导致同名重注册撞 DB 约束；C-23② 所称"归属校验发生在鉴权前"经复核不成立——认证前业务消息一律不响应），并如实登记三处**此前被过度声明的"完成"**：配额管理只有后端 API 而无管理页入口、用量统计实为"近 1/7/30 天个人语音聚合"且不过滤通话状态也不含文本消息、辅助调试能力（人设模板 / 快捷命令 / 消息搜索 / 对话导出）只接在语音工作台。v2.27 为 **AI 工具扩展批次**：助手新增文生图（`generate_image`）、文生视频（`generate_video` 提交 + `query_video` 查询，拆两个工具是因为工具调用在对话流内同步执行、不能在单工具里轮询等出片）、网页正文读取（`fetch_webpage`，免第三方密钥但默认关闭）；架构上收口为"一个工具类即一个能力"——新增类只需暴露 `ToolCallback` Bean 并被自动收集，依赖密钥/开关未配置时用 `@RequiresProperty` 让工具**根本不注册**（此前 `get_weather`/`web_search` 在未配 Key 时仍暴露给模型、只能执行时报错），工具重名由静默覆盖改为启动即失败；前端 `markdown.ts` 补图片渲染，生图结果不再只是裸链接。图像/视频按 OpenAI 兼容契约实现，**尚未持有真实服务商账号做过一次调用**（详见手册 6.6 与 7.4 C-32~C-36）。v2.28 为**能力扩展与按助手裁剪批次**：新增 `deep_research`（把"检索 + 逐篇抓取正文 + 汇总带来源摘要"压缩进**单次工具调用**——每轮工具迭代都要重跑一次完整流式模型调用，语音场景即一轮播报延迟，故不靠模型自己串两次工具；组合既有 `web_search`/`fetch_webpage` 而非复制其网络与安全逻辑，SSRF 校验、关闭重定向、类型与字节上限全部沿用）；工具能力**按助手裁剪**（`assistants.tools` 白名单 + 三条对话链路统一过滤 + `GET /api/tools` 工具字典 + 前端"可用工具"勾选，空配置=全部可用，故既有助手零迁移即行为不变；**工具集在 WS 连接建立时快照，改配置需重进助手或下次通话才生效**）；`@RequiresProperty` 的两套开关表达收敛为内联 `key=value`（判定结果不变）。**已部署库需手工执行一次 `ALTER TABLE assistants ADD COLUMN tools ...`**（手册 5.4 已登记语句与不执行的后果；本批只在本机开发库执行过，其他环境仍需各自执行，服务侧未做接口级端到端验证）。同时手册新增 [6.7 与豆包能力横向对比与差距收口路线](docs/云谕助手项目手册.md#67-与豆包能力横向对比与差距收口路线)：14 行能力矩阵按"持平 / 契约接线 / 架构 / 验证债"四类归因，结论是豆包多数模型能力可采购、本项目的差异化在治理与私有化，**而最贵的差距是"语音主链路从未真机跑通"这条验证债**（豆包侧信息仅来自公开产品页与 App Store 描述，未逐项实操核对）。v2.29 为**上线就绪批次（零产品功能新增）**：把"能不能公网给真实小范围用户用"从隐含假设变成显式清单——① 配额运营闭环（`Admin.vue` 新增「用量配额」Tab + `GET /api/admin/quotas/defaults`，并修掉"给新作用域配单项配额会建出 NULL 维度、下次拦截即 500"；**开工前提"注册即挂默认日配额"经复核不成立，环境变量本就兜底，故按实况改向**）；② 用户输入 2000 字上限（配额按条计量不限长度＝成本可直接打穿，取值刻意落在 Tomcat 默认 8KB 入站帧内以免表现为断连）；③ `OPENAI_API_KEY` 去掉占位默认改为缺失即启动失败、`/actuator/health` 明细默认关闭（`/actuator` 免鉴权）；④ `index.sql` 拆为"新库建表 / `scripts/db-migrate.sh` 幂等增量 / `db/seed-demo.sql` 演示数据"三份（演示账号不再随建库进生产，手工 ALTER 变成有账本的迁移文件）；⑤ `scripts/backup-mysql.sh` + 恢复演练（此前"每日全量 + binlog 增量"里增量部分从未落地）、日志可落文件且修掉 `log-impl=StdOutImpl` 让新加的 SQL 级别开关成为空壳的问题；⑥ 手册新增 **5.10 公网试验部署清单**（安全组端口 / 反代四易漏项 / systemd 启停 / 备份异地 / 关停与数据处置）与 `.env` 真实加载方式的纠正。**验证口径不变**：后端进程在本机从未启动过（沙箱拦截），故配额页与 defaults 端点只有单测与类型层保证；5.10 是"由代码事实反推的操作序列"而非跑通过的记录；语音仍未过真网关。另如实登记一次**开发库被误删事故**（`index.sql` 的 `DROP DATABASE` 在派生脚本时漏网）与当天备份恢复全过程，规则已写入手册 5.2/5.4（详见 7.4 C-44~C-56）。v2.30 为**可验证性收口批次（零产品功能新增，C-57~C-60）**：① 前端静默续期收口（`api/auth.ts` 统一出口 + 单飞续期 + 跨标签页补偿 + WS 每次重连重取令牌，修掉"访问令牌 24h 到期后全站 401 且不跳登录"的死路；新增 `npm run check:auth` 门禁，Node 原生类型擦除直调 `auth.ts` 跑 9 场景，零新依赖）；② `SERVER_ADDRESS` / `MANAGEMENT_SERVER_PORT` / `MANAGEMENT_SERVER_ADDRESS` / `CORS_ALLOWED_ORIGINS` 四项提为环境变量（**CORS 与 WS 握手来源白名单此前硬编码 4 个本地端口，属上线阻断级：公网站点漏配即"能登录、点什么都没反应"且后端无异常栈**；默认值不变＝零行为变更）；③ 过程中自纠一条框架实况——Boot 的独立管理端口**不继承** `server.address`，直觉上的"加固"写法反而更暴露，已按字节码核实后修正并写入手册 6.1；④ `AdminControllerTest` 14 例（锁死 v2.29 的配额拆箱 NPE 回归）+ **`scripts/smoke.sh`** 9 节 61 项接口/WS 冒烟，专管单测覆盖不到的拦截器、序列化、限流与握手。**验证口径**：后端进程在本机仍从未启动过（权限策略拦截），故冒烟脚本只在假 `curl` 桩上自证过自身逻辑（全量参数 61 通过 / 0 失败 / 2 跳过），**尚未对真实后端执行**——它连同 5.10 清单构成"等首次真机运行"的验收资产（手册 7.4 候选 ⑲）。v2.31 为**真实首跑批次（零产品功能新增，C-61~C-62）**：后端进程**第一次在本机真实启动**（`SERVER_ADDRESS=127.0.0.1` + 业务 8091 / 管理 9091，只监听回环），上面"从未启动过"的验证口径自此作废——首跑即抓出两个只有真跑才会暴露的缺陷：**C-61** v2.30 给 `management.server.address` 写的回落默认值让**默认配置在任何机器上都启动失败**（Boot 在管理端口与业务端口相同时，该项非 null 即抛 `IllegalStateException`；结论此前只从字节码读出、未运行过），修法为 yaml 删掉该键 + 新增 `ManagementAddressGuard` 在启动时断言"独立管理端口必须成对设监听地址"；**C-62** 未匹配路径被 `@ExceptionHandler(Exception.class)` 兜底降级成 HTTP 500（浏览器每请求一次 `/favicon.ico` 就计一条服务端错误），已补 `NoResourceFoundException` → 404 处理器。`scripts/smoke.sh` 首次打到真实实例：暴露面 / 端口分离 / 401 与新增的 404 断言通过，写路径因本地 `.env` 的 DB 凭据仍是占位值而未执行（手册 6.6 已登记为批次边界）；后端单测 43 类 / 275 例全绿，前端源码零改动。v2.32 为**深入探查 + 端到端全链路验证批次（零产品功能新增，C-63~C-65）**：对同一台真实实例做 HTTP 链路 / WS 帧级 / 后端回归三层探测，三条缺陷全部由实测产出——**C-63** 所有会话凭据入口只验签名与过期、**不看 `type` claim**，7 天寿命的 refresh 令牌因此可直接冒充 access 使用（泄露 refresh＝泄露整个 API，且前端静默续期会把这种误用洗成一次成功请求），收口为 `JwtUtil.validateAccessToken()` 并切换五个消费点（实测 refresh 打 `/api/assistants` → 401）；**C-64** 客户端把请求发错形状（方法 / Content-Type / 缺 multipart 字段 / 超体积）被全局兜底降级成 **HTTP 500 + 带栈日志**，计入服务端错误率，补 405（含 `Allow`）/415/400/413 四条精确处理器（实测 500→对应 4xx，真实实例日志 0 条兜底 ERROR）；**C-65** 免令牌握手的连接在服务端**永不过期**——Tomcat 10.1.24 不回收服务端会话、`setMaxIdleTimeout()` 在服务端是空操作（实测修前 400 秒仍存活），新增 `UnauthenticatedSocketReaper` 按 `WS_UNAUTHENTICATED_IDLE_MS`（默认 30s）清扫，修后实测 30.6 秒关闭且已认证连接全程不受影响。后端单测升至 **47 类 / 301 例**全绿，`scripts/smoke.sh` 新增 §7.5 请求形状节与 §2 凭据节。同批把**探针自身**也当成验证对象：前两轮曾因 `curl` 无法握手 WS、令牌铸造器漏写文件而产出"看起来通过"的假绿，已改为过期即退出 + 显式关闭码断言。**验证口径的残余不变**：写路径与浏览器级 E2E 仍缺一份可用本地库凭据（`.env` 的 `DB_USER`/`DB_PASSWORD` 为 `[REQUIRED]` 占位）。已知限制（OpenAPI 外呼媒体回流不实现、语音主链路未经真网关端到端验证、统计口径偏差、备份无 PITR、`/actuator` 免鉴权需网络层兜底等）详见手册 [6.6 已知限制与演进方向](docs/云谕助手项目手册.md#66-已知限制与演进方向) 与 [7.4 问题追踪与修复记录](docs/云谕助手项目手册.md#74-问题追踪与修复记录)。

**迭代路线图**（P0~P2 详见手册 6.6；v2.28 起以 6.7 的横向对比为取舍依据）：

- **P0 工程基建与安全加固**：✅ 会话（Session）模型落地 · ✅ refresh token + 登出黑名单 + 登录失败锁定 · ✅ 单测与 ESLint 质量基建
- **P1 产品能力补齐**：✅ 助手列表分页与搜索 · ✅ Redis 多实例支撑 · ✅ 调试时间线可视化 · ✅ 角色体系与用户管理
- **P2 规模运营与体验优化**：✅ 长会话消息惰性分页 + 数据归档与容量治理 · ✅ 通话录音与回放 + 弱网降级 + ICE 可配置化（TURN 部署待实施；录音所属的语音 AI 主链路待真网关复测） · ✅ 组织数据隔离 + 用量配额账单 + 开放 OpenAPI（配额**已接通管理页**：v2.29 起 `Admin.vue` 有「用量配额」Tab，此前只有后端 API）

## 健康检查

- `GET /actuator/health`（**v2.29 起默认只回 `{"status":"UP"}`**，db / ping / disk 明细由 `HEALTH_SHOW_DETAILS` 控制；`/actuator` 不在鉴权拦截器覆盖范围内，公网实例须由安全组 / 反向代理封住，见手册 6.1）
- `GET /actuator/metrics`、`GET /actuator/info`（暴露范围已在 `application.yaml` 显式限定，不含 `env` / `heapdump` 等高敏端点）
- 后端启动成功本身即是配置完整性的检查：`DB_USER` / `DB_PASSWORD` / `JWT_SECRET` / `OPENAI_API_KEY` 缺任一项即启动失败
- **接口级冒烟（v2.30 起，v2.31 首次对真实实例执行）**：`scripts/smoke.sh` 对**已运行的实例**打一遍 HTTP 与 WS 链路（10 节：actuator 暴露面与端口分离、未匹配路径返回 404 而非 500、401/403 与伪造令牌、**refresh 令牌不得当会话凭据（v2.32）**、注册→me→refresh 轮换、助手/会话 CRUD 写后读、配额结构、WS 握手与站点 Origin 白名单、**请求形状错误 405（带 `Allow`）/415/畸形 JSON/缺上传字段（v2.32 新增 §7.5）**、管理端只读、登出后黑名单）——单测证明方法行为，这里证明装配、拦截器、序列化与握手。用法 `BASE=... MGMT_BASE=... SMOKE_ORIGIN=... scripts/smoke.sh`，退出码 0/1/2，边界与不触碰的接口见脚本头部注释