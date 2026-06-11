# 云谕助手 (yunyu-assistant) — 项目需求文档

> 版本: v1.0 | 日期: 2026-06-10 | 状态: 开发中

---

## 一、项目概述

### 1.1 产品定位
**云谕助手** 是一个基于 AI 大模型的智能对话助手平台。用户可以创建多个自定义人格的 AI 助手，通过文字或语音进行交互，并可关联知识库实现 RAG（检索增强生成）增强问答。

### 1.2 核心价值主张
- **多助手管理**: 创建、配置、管理多个独立人格的 AI 助手
- **双模交互**: 文字聊天 + 实时语音通话（ASR → AI → TTS 全链路）
- **知识库增强**: 接入 RAGFlow 知识库，让 AI 基于私有文档回答问题
- **工具调用**: AI 可自动调用搜索/天气/时间等外部工具
- **静谧美学**: 莫兰迪色调 + 衬线排版 + 三模式主题切换

### 1.3 技术栈

| 层级 | 技术选型 |
|------|----------|
| 前端框架 | Vue 3.5 + TypeScript + Vite 8 |
| UI 方案 | TailwindCSS 3.4 + 自定义莫兰迪设计系统 |
| 状态管理 | Pinia 3 |
| 后端框架 | Spring Boot 3.4.5 + Java 21 |
| 数据库 | MySQL 8.x + MyBatis |
| AI 引擎 | Spring AI 1.0 (DashScope/Qwen 兼容) |
| 实时通信 | WebSocket (文本) + WebRTC (语音) |
| 知识库 | RAGFlow |
| 认证方式 | JWT (jjwt 0.12.6) |

### 1.4 目标用户
- 个人用户：需要一个可定制的 AI 助手进行日常问答和知识检索
- 小团队：需要内部知识库驱动的智能客服或文档助手

---

## 二、功能需求清单

### 2.1 用户认证模块 (P0)

| 功能 | 描述 | 优先级 | 当前状态 |
|------|------|--------|----------|
| 用户注册 | 用户名 + 密码注册，加盐 SHA-256 哈希存储 | P0 | ✅ 完成 |
| 用户登录 | 返回 JWT Token，有效期 7 天 | P0 | ✅ 完成 |
| 获取当前用户 | 根据 Token 返回用户信息 | P0 | ✅ 完成 |
| 修改密码 | 验证旧密码后更新新密码 | P1 | ❌ 缺失 |
| 登出通知 | 服务端记录登出日志（可选 token 黑名单） | P2 | ⚠️ 部分完成 |

### 2.2 助手管理模块 (P0)

| 功能 | 描述 | 优先级 | 当前状态 |
|------|------|--------|----------|
| 创建助手 | 名称 + 描述 + 人格设定 | P0 | ✅ 完成 |
| 助手列表 | 按当前用户过滤展示 | P0 | ✅ 完成 |
| 助手详情 | 获取单个助手完整信息 | P0 | ✅ 完成 |
| 更新助手 | 修改名称/描述/人格/知识库关联 | P0 | ✅ 完成 |
| 删除助手 | 软删除或硬删除 | P0 | ✅ 完成 |
| 克隆助手 | 复制一个助手的全部配置 | P1 | ✅ 前端完成 |
| 助手搜索/筛选 | 按名称模糊搜索 | P2 | ❌ 缺失 |

### 2.3 对话模块 (P0 — 核心功能)

| 功能 | 描述 | 优先级 | 当前状态 |
|------|------|--------|----------|
| 文本对话 | WebSocket 流式输出 AI 回复 | P0 | ✅ 完成 |
| Function Calling | AI 自动调用工具（搜索/天气/时间）并回传结果继续推理 | P0 | ✅ 完成 |
| 对话历史加载 | 连接时从 DB 加载历史消息 | P0 | ✅ 完成 |
| 对话历史保存 | 断开连接时保存到 DB | P0 | ✅ 完成 |
| 重置对话 | 清空上下文重新开始 | P0 | ✅ 完成 |
| 切换人格 | 动态修改 System Prompt | P1 | ✅ 完成 |
| Markdown 渲染 | AI 回复支持代码块/表格/列表渲染 | P1 | ❌ 缺失 |
| 消息搜索 | 在当前会话中搜索历史消息关键词 | P2 | ⚠️ composable 已完成，UI 未接入 |
| 导出对话 | 导出为 Markdown / JSON 文件 | P2 | ⚠️ 工具函数已完成，UI 未接入 |
| 快捷命令 | 预设常用提示词一键发送 | P2 | ⚠️ composable 已完成，UI 未接入 |

### 2.4 语音通话模块 (P1)

| 功能 | 描述 | 优先级 | 当前状态 |
|------|------|--------|----------|
| 发起语音通话 | WebRTC 音频采集 + SDP 协商 | P1 | ✅ 完成 |
| ASR 语音识别 | 实时语音转文字（DashScope） | P1 | ✅ 完成 |
| TTS 语音合成 | AI 回复转语音播放（DashScope） | P1 | ⚠️ **链路断裂** |
| 语音网关对接 | RustPBX WebSocket 信令中转 | P1 | ✅ 完成 |
| 音频电平可视化 | 麦克风音量实时显示 | P2 | ✅ 完成 |

### 2.5 知识库模块 (P1)

| 功能 | 描述 | 优先级 | 当前状态 |
|------|------|--------|----------|
| 知识库列表 | 展示 RAGFlow 中所有数据集 | P1 | ✅ 完成（前端直连 RAGFlow） |
| 创建知识库 | 在 RAGFlow 中创建新数据集 | P1 | ✅ 完成 |
| 删除知识库 | 批量删除数据集 | P1 | ✅ 完成 |
| 上传文档 | 上传 PDF/TXT/DOC/MD 等文件 | P1 | ✅ 完成 |
| 解析文档 | 触发 RAGFlow 文档切片解析 | P1 | ✅ 完成 |
| 管理文档 | 查看/删除已上传文档 | P1 | ✅ 完成 |
| 关联知识库 | 为助手选择关联的知识库（单选/多选） | P1 | ✅ 完成 |
| RAG 增强 | 对话时自动检索相关知识注入上下文 | P1 | ✅ 完成 |
| 后端代理 | 所有 RAGFlow 操作经后端代理转发 | P2 | ❌ 缺失（当前前端直连） |

### 2.6 UI/UX 模块

| 功能 | 描述 | 优先级 | 当前状态 |
|------|------|--------|----------|
| 莫兰迪设计系统 | 低饱和度色板 + 衬线标题 + 噪点纹理 | P0 | ✅ 完成 |
| 三模式主题切换 | 亮色 / 暗色 / 跟随系统 | P0 | ✅ 完成 |
| 主题过渡动画 | 300ms 平滑过渡 | P0 | ✅ 完成 |
| 骨架屏加载 | 首次加载占位效果 | P2 | ⚠️ 组件已完成未使用 |
| 键盘快捷键 | Enter 发送/Esc 关弹窗/Ctrl+Enter 等 | P2 | ❌ 缺失 |
| 密码可见性切换 | 显示/隐藏密码 | P2 | ❌ 缺失 |
| 拖拽上传文件 | 支持拖拽到上传区域 | P2 | ❌ 缺失 |

---

## 三、非功能性需求

### 3.1 安全需求

| 需求 | 说明 | 当前状态 |
|------|------|----------|
| JWT 认证 | HTTP + WebSocket 双通道认证 | ✅ 完成 |
| 密码哈希 | 加盐哈希（当前 SHA-256，待迁移 BCrypt） | ⚠️ 弱算法 |
| SQL 注入防护 | MyBatis `#{}` 参数化绑定 | ✅ 安全 |
| IDOR 防护 | 资源操作校验归属权 userId | ✅ 已修复 |
| CORS 策略 | 收窄允许的 Origin 白名单 | ✅ 已修复 |
| WebSocket Origin | 收窄允许的源 | ✅ 已修复 |
| API Key 保护 | 不再向前端下发第三方密钥 | ✅ 已修复 |
| 敏感配置 | 移除硬编码密钥默认值 | ✅ 已修复 |
| 输入校验 | 注册/登录参数非空+长度限制 | ✅ 已修复 |
| 请求频率限制 | 接口防刷 | ❌ 缺失 |

### 3.2 性能需求

| 需求 | 目标 | 当前状态 |
|------|------|----------|
| 流式响应延迟 | 首个 token < 2s | ⚠️ 取决于 AI 服务 |
| WebSocket 心跳 | 连接保活机制 | ❌ 缺失 |
| 路由懒加载 | 大组件按需加载 | ❌ 缺失 |
| 数据库索引 | user_id 字段索引 | ❌ 缺失 |
| 聊天记录存储 | JSON 字段膨胀问题 | ⚠️ 待优化 |

### 3.3 可用性需求

| 需求 | 说明 | 当前状态 |
|------|------|----------|
| WebSocket 重连 | 断线自动重连 | ❌ 缺失 |
| 错误提示 | 统一 Toast 通知组件 | ⚠️ 分散在各处 |
| 加载状态 | API 请求 loading 指示 | ⚠️ 部分 |
| 空状态引导 | 无数据时的友好提示 | ✅ 基本完成 |
| 404 页面 | 友好的错误页面 | ✅ 完成 |

---

## 四、数据库设计

### 4.1 ER 图概览

```
┌──────────────┐         ┌──────────────────┐         ┌─────────────┐
│   app_user   │  1    N │    assistant      │  1    N │ chat_message│
├──────────────┤─────────├──────────────────┤─────────├─────────────┤
│ id (PK)      │◄────────│ id (PK)          │◄────────│ id (PK)     │
│ name (UQ)    │         │ name             │         │ assistant_id│
│ password     │         │ description      │         │ role        │
│ salt         │         │ personality      │         │ content     │
│ created_at   │         │ chat_message(JSON)┘(废弃)  │ tool_calls  │
│ updated_at   │         │ knowledge_ids    │         │ cost_time   │
└──────────────┘         │ voice            │         │ created_at  │
                         │ user_id (FK)     │         └─────────────┘
                         │ created_at       │
                         │ updated_at       │
                         └──────────────────┘
```

### 4.2 表结构详细定义

#### 表: app_user（用户表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | VARCHAR(36) | PK, UUID | 用户唯一标识 |
| name | VARCHAR(100) | NOT NULL, UNIQUE, INDEX | 用户名 |
| password | VARCHAR(255) | NOT NULL | 加盐哈希密码 |
| salt | VARCHAR(44) | 可空 | Base64 编码的 16 字节随机盐 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | AUTO UPDATE | 更新时间 |

#### 表: assistant（助手表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | VARCHAR(36) | PK, UUID | 助手唯一标识 |
| name | VARCHAR(100) | NOT NULL, INDEX | 助手名称 |
| description | TEXT | 可空 | 助手描述 |
| personality | TEXT | 可空 | 人格/系统提示词 |
| voice | VARCHAR(50) | 可空 | TTS 音色名称 |
| user_id | VARCHAR(36) | NOT NULL, INDEX(FK) | 所属用户 ID |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | AUTO UPDATE | 更新时间 |

> **变更说明**: 移除 `messages`(死字段)、`chat_message`(JSON冗余)、`knowledge_ids`(JSON关联)，改为独立的 `chat_message` 表。

#### 表: chat_message（聊天消息表）【新增】

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 消息自增主键 |
| assistant_id | VARCHAR(36) | NOT NULL, INDEX | 所属助手 ID |
| role | ENUM('user','assistant','tool_call','tool_result') | NOT NULL | 消息角色 |
| content | TEXT | NOT NULL | 消息内容 |
| tool_name | VARCHAR(100) | 可空 | 工具名称(tool_call/tool_result时) |
| tool_args | JSON | 可空 | 工具参数(tool_call时) |
| tool_result | JSON | 可空 | 工具执行结果(tool_result时) |
| cost_time | BIGINT | 可空 | AI 回复耗时(ms) |
| knowledgebase_info | JSON | 可空 | 引用的知识库信息 |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP, INDEX | 消息时间 |

### 4.3 索引策略

```sql
-- app_user 表
CREATE UNIQUE INDEX uk_user_name ON app_user(name);

-- assistant 表
CREATE INDEX idx_assistant_user_id ON assistant(user_id);
CREATE INDEX idx_assistant_name ON assistant(name);

-- chat_message 表
CREATE INDEX idx_cm_assistant_id ON chat_message(assistant_id);
CREATE INDEX idx_cm_created_at ON chat_message(created_at);
CREATE INDEX idx_cm_assistant_created ON chat_message(assistant_id, created_at);
```

### 4.4 DDL 变更脚本

```sql
-- ====== 变更1: 添加 salt 列（如果尚未添加）======
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS salt VARCHAR(44) AFTER PASSWORD;

-- ====== 变更2: 新增 chat_message 表 ======
CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assistant_id VARCHAR(36) NOT NULL,
    role ENUM('user', 'assistant', 'tool_call', 'tool_result') NOT NULL,
    content TEXT NOT NULL,
    tool_name VARCHAR(100) DEFAULT NULL,
    tool_args JSON DEFAULT NULL,
    tool_result JSON DEFAULT NULL,
    cost_time BIGINT DEFAULT NULL,
    knowledgebase_info JSON DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cm_assistant_id (assistant_id),
    INDEX idx_cm_created_at (created_at),
    INDEX idx_cm_assistant_created (assistant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ====== 变更3: 添加 assistant 表索引 ======
CREATE INDEX IF NOT EXISTS idx_assistant_user_id ON assistant(user_id);

-- ====== 变更4: 迁移现有 JSON 聊天记录到新表 ======
-- （一次性迁移脚本，由后端启动时检测执行）
-- INSERT INTO chat_message (assistant_id, role, content, tool_name, tool_args, tool_result, cost_time, created_at)
-- SELECT a.id, m.role, m.message, ... FROM assistant a, JSON_TABLE(a.chat_message, '$[*]' ...) AS m WHERE a.chat_message IS NOT NULL AND a.chat_message != '[]' AND a.chat_message != 'NULL';
```

---

## 五、已知 Bug 清单

| # | Bug | 严重程度 | 位置 | 影响 |
|---|-----|----------|------|------|
| B-01 | 语音模式 TTS 自动播放链路断裂 | **严重** | VoiceSignalingHandler.handleAsrResult() | 语音通话模式下 AI 回复无法转为语音播报 |
| B-02 | ChatRobot 路由未注册 | **中等** | router/index.ts | 对话测试页面无法通过 URL 访问 |
| B-03 | SmartRobot.vue 单文件 1550 行 | **中等** | views/SmartRobot.vue | 维护困难，应拆分子组件 |
| B-04 | Composables 全部被视图绕过 | **中等** | SmartRobot/ChatRobot | ~2000 行重复代码 |
| B-05 | Pinia Store 与视图脱节 | **低** | stores/ + views/ | 状态管理形同虚设 |

---

## 六、实施计划

### Phase 1: 数据库 + 后端核心修复（当前阶段）
1. 执行 DDL 变更：salt 列 + chat_message 表 + 索引
2. 新增 ChatMessage Entity + Mapper + Service
3. **修复 B-01**: VoiceSignalingHandler TTS 断链
4. 新增修改密码接口
5. 完善 KnowledgeController（后端代理 RAGFlow）
6. ChatService 改用 chat_message 表持久化

### Phase 2: 前端架构优化
1. **修复 B-02**: 注册 ChatRobot 路由
2. **修复 B-03**: SmartRobot 拆分为子组件
3. **修复 B-04**: 视图层集成已有 composables
4. **修复 B-05**: 统一使用 Pinia store
5. 接入缺失功能 UI：搜索/导出/快捷命令/骨架屏
6. 路由懒加载优化
7. Markdown 渲染支持

### Phase 3: 体验打磨
1. WebSocket 重连 + 心跳机制
2. 键盘快捷键
3. 密码可见性切换
4. 拖拽上传
5. 加载状态统一
6. 单元测试补充

---

## 七、验收标准

### 必须满足（Go-Live 条件）
- [ ] 用户可以注册/登录/登出完整流程
- [ ] 可以创建/编辑/删除助手
- [ ] 可以与助手进行文字对话（流式输出正常）
- [ ] Function Calling 工具调用循环正常运行
- [ ] 语音通话全链路可用（ASR → AI → TTS）
- [ ] 知识库 CRUD 和文档上传/解析正常
- [ ] 亮色/暗色/跟随系统三种主题切换正常
- [ ] 所有 HIGH 级安全漏洞已修复
- [ ] 数据库 schema 与代码实体一致

### 期望满足（体验加分项）
- [ ] 聊天记录可在刷新后恢复
- [ ] 支持消息搜索和导出
- [ ] 支持 Markdown 渲染
- [ ] 有基本的错误提示和加载状态
