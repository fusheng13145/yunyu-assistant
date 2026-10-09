# 云谕助手（yunyu-assistant）

> 面向开发与运营人员的「AI 语音助手搭建 + 模型调试」一体化 Web 平台。配置即用、边聊边调、语音与文本双通道、知识可控、全程可审计。

## 功能

| 模块 | 能力 |
|---|---|
| 账号认证 | 注册/登录/改密，JWT 双令牌静默续期，登录限流防爆破，三态登录（用户名/邮箱/手机号），注册邀请码可选 |
| 助手管理 | 助手 CRUD（软删+属主校验）、人设/音色/模型参数、知识库绑定、工具白名单按助手裁剪 |
| 语音通话 | WebRTC 建链、ASR 实时转写、LLM 流式回复、TTS 播报、VAD 打断、通话录音与回放、弱网降级 |
| 文本对话 | 流式对话、人设热更新、知识库动态切换、工具调用可视化、失败回合落库 |
| 知识库 | RAGFlow 双层代理（密钥零下发）、文档上传/删除、多库联合检索 |
| 用量配额 | 助手上限、单日通话次数/时长/消息量拦截、用量账单、管理端配额配置页 |
| 组织协作 | 组织/团队成员管理（owner/editor/viewer）、资源共享与数据隔离 |
| 开放 OpenAPI | 第三方应用 API Key、文本对话 SSE、WebRTC 语音会话、PSTN 外呼、Webhook 回调 |
| AI 工具 | 时间、天气、联网搜索、深度研究、文生图/文生视频、网页正文读取、挂断 |
| 工程化 | 操作审计、全局异常脱敏、健康检查、环境变量化配置、逻辑删除、统一响应体 |

## 技术栈

- **后端**：Spring Boot 3.5 · Java 21 · MyBatis-Plus 3.5 · MySQL 8 · Spring AI 1.0 · Spring WebSocket · JJWT
- **前端**：Vue 3.5 · TypeScript · Vite 6 · vue-router 4 · TailwindCSS 3 · WebRTC · lucide-vue-next

## 快速开始

### 前置依赖

JDK 21、Node.js ≥ 20、MySQL 8。必需 LLM API（OpenAI 兼容 endpoint 与 Key）。可选：RAGFlow、RustPBX 语音网关、搜索/天气/图像/视频 API——未配置时对应工具不注册，不影响主流程。

### 1. 初始化数据库

```bash
# 新环境建表（开头含 DROP DATABASE，只在第一次执行）
mysql --default-character-set=utf8mb4 -uroot -p < backend/src/main/resources/index.sql
# 已部署库每次上线前跑增量迁移
DB_USER=... DB_PASSWORD=... bash scripts/db/db-migrate.sh
```

> ⚠️ `index.sql` 不要灌向有数据的实例。上线顺序固定为"先迁移、再上新代码"。

### 2. 配置环境变量

复制 `.env.example` 为 `.env`，填 `DB_USER`/`DB_PASSWORD`/`JWT_SECRET`（≥32 字节随机）/`OPENAI_API_KEY`。其余选配项见 `.env.example` 注释。

> Spring Boot 不自动读 `.env`（无 dotenv 依赖）。需显式导出：`set -a && . ./.env && set +a && java -jar ...`。`start-backend.bat` 不读 `.env`，它自带开发默认值。

### 3. 启动

- 双击 `start-backend.bat` → http://localhost:8080
- 双击 `start-frontend.bat` → http://localhost:5173

或命令行：

```bash
# 后端
cd backend && ./mvnw spring-boot:run
# 前端
cd frontend && npm install && npm run dev
```

浏览器打开 http://localhost:5173 → 注册 → 创建助手 → 对话或通话。

## 文档

**权威层只有一份**：[docs/云谕助手项目手册.md](docs/云谕助手项目手册.md)。其余各页是导读层——按角色把注意力引到手册对应章节，本身不重复计数值，与手册冲突时以手册为准。

| 页面 | 什么时候看 |
|---|---|
| [AGENTS.md](AGENTS.md) | 动手前：交付标准、批次节奏、不可逆资源、安全与验证纪律 |
| [docs/PROJECT-SPEC.md](docs/PROJECT-SPEC.md) | 判断"该不该做"：定位、范围、明确不做的事 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 动跨模块代码前：分层、拦截器链、WS 通道 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 日常开发：环境、提交前必跑、写迁移的规矩 |
| [docs/PAGE-STRUCTURE.md](docs/PAGE-STRUCTURE.md) | 改页面时：路由→视图→接口→WS 的对应关系 |
| [docs/COMPONENT-GUIDELINES.md](docs/COMPONENT-GUIDELINES.md) | 写组件前：命名、错误处理、依赖策略 |
| [docs/REGISTRY.md](docs/REGISTRY.md) | 加工具/端点/列时：六张登记表 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 上线相关：拓扑、上线顺序、易漏项 |
| [DESIGN.md](DESIGN.md) | 改前端样式前：视觉规范 |
| [CHANGELOG.md](CHANGELOG.md) | 查历史版本（正文在手册 7.5） |
| [TODO.md](TODO.md) | 找下一步：当前进度与待表态事项 |
