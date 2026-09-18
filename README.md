# 云谕助手（yunyu-assistant）

> 面向开发与运营人员的「AI 语音助手搭建 + 模型调试」一体化 Web 平台。
> 配置即用、边聊边调、语音与文本双通道、知识可控、全程可审计。

## 功能特性

| 模块 | 能力 |
|---|---|
| 账号认证 | 注册 / 登录 / 修改密码 / 个人资料，JWT 无状态鉴权，登录限流防爆破 |
| 助手管理 | 助手增删改查（软删除 + 属主校验）、人设 / 音色 / 模型参数配置、知识库绑定、人设模板 |
| 语音通话 | WebRTC 建链、ASR 实时转写、LLM 流式回复、TTS 播报、VAD 打断、沉默追问、LLM 主动挂断、通话记录、**通话录音（本地合成上传与回放）**、弱网自动降级 |
| 文本对话 | 流式对话、人设热更新、知识库动态切换、对话重置、工具调用可视化、Markdown 渲染 |
| 知识库 | RAGFlow 双层后端代理（密钥零下发）、文档上传 / 删除 / 切片解析、多库联合检索、检索效果测试 |
| 记录与统计 | 通话记录列表 / 详情、按日 / 周 / 月用量统计 |
| AI 工具 | 挂断（hangup）、当前时间、天气（高德）、联网搜索，支持 Function Calling 递归续答 |
| 工程化 | 操作审计、全局异常脱敏、健康检查、环境变量化配置、逻辑删除、统一响应体 |

## 技术栈

**后端**：Spring Boot 3.5 · Java 21 · MyBatis-Plus 3.5 · MySQL 8 · Spring AI 1.0（OpenAI 兼容）· Spring WebSocket · JJWT · OkHttp

**前端**：Vue 3.5 · TypeScript · Vite 6 · Pinia · vue-router 4 · TailwindCSS 3 · WebRTC · lucide-vue-next

## 目录结构

```
yunyu-assistant/
├── backend/                 # Spring Boot 后端（单模块）
│   └── src/main/
│       ├── java/com/leyon/backend/   # controller/service/mapper/entity/handler/interceptor/tool
│       └── resources/                # application.yaml、index.sql（建库与种子数据）
├── frontend/                # Vue3 前端（Vite）
│   └── src/                 # api/composables/router/stores/utils/views/components
├── docs/                    # 项目手册（详见《云谕助手项目手册.md》）
├── start-backend.bat        # 一键启动后端（双击）
├── start-frontend.bat       # 一键启动前端（双击）
└── .env.example             # 环境变量模板（复制为 .env 后填写）
```

## 快速开始

### 前置依赖

- JDK 21、Node.js ≥ 20、MySQL 8（需运行中）
- 可选（未配置时对应功能降级）：LLM API（OpenAI 兼容）、RAGFlow、RustPBX 语音网关、搜索 / 天气 API

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
| `SEARCH_API_KEY` / `SEARCH_ENDPOINT` | 联网搜索工具 |
| `WEATHER_API_KEY` | 高德天气工具 |

## 文档

- 项目手册：[docs/云谕助手项目手册.md](docs/云谕助手项目手册.md)（项目概述 / 功能说明 / 技术架构 / 开发规范 / 部署流程 / 维护指南 / API 与 WS 协议速查）

## 项目现状与迭代方向

**当前完成度**：核心链路（认证 / 助手管理 / 文本对话 / 语音通话 / 知识库 / 记录统计 / AI 工具 / 审计）均已实现并通过本地验证；前端已整体重构为纯白极客风格并通过 `npm run build`。P0 工程基建已落地（v2.5）：会话（Session）模型、refresh token + 登出黑名单 + 登录锁定、后端 37 项核心链路单测、前端 ESLint 门禁。P1 产品能力补齐已全部落地（v2.6~v2.9）：助手分页与搜索、Redis 多实例支撑、调试时间线、角色体系与用户管理。P2 已部分落地：长会话消息惰性分页（v2.10）、ICE 服务器可配置化 / 通话录音与回放 / 弱网降级（v2.11~v2.12）、**数据归档与容量治理**（v2.13，records/call_records/audit_logs 归档 + 录音清理，管理端触发 + 可选定时，后端单测 78 个全绿）。已知限制（TURN 实际部署、定时归档跨实例防重、单实例内存限流等）详见手册 [6.6](#) 。

**迭代路线图**（详见手册 6.6）：

- **P0 工程基建与安全加固**：✅ 会话（Session）模型落地 · ✅ refresh token + 登出黑名单 + 登录失败锁定 · ✅ 单测与 ESLint 质量基建
- **P1 产品能力补齐**：✅ 助手列表分页与搜索 · ✅ Redis 多实例支撑 · ✅ 调试时间线可视化 · ✅ 角色体系与用户管理
- **P2 规模运营与体验优化**：✅ 通话录音与回放 + 弱网降级 + ICE 可配置化（TURN 部署待实施） · ✅ 长会话消息惰性分页 + 数据归档与容量治理 · 多租户与商业化前置

## 健康检查

- `GET /actuator/health`（含 db / ping / disk 明细）
- `GET /actuator/metrics`、`GET /actuator/info`