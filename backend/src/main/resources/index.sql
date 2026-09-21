-- ----------------------------
-- 数据库: yunyu_assistant
-- ----------------------------
DROP DATABASE IF EXISTS `yunyu_assistant`;
CREATE DATABASE `yunyu_assistant` DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
USE `yunyu_assistant`;

-- ----------------------------
-- 用户表: user
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
    `id` VARCHAR(36) NOT NULL COMMENT '用户UUID',
    `username` VARCHAR(100) UNIQUE NOT NULL COMMENT '用户名',
    `nickname` VARCHAR(100) DEFAULT NULL COMMENT '昵称',
    `password` VARCHAR(255) NOT NULL COMMENT '加盐哈希密码',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `role` VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色 user:普通用户 admin:管理员',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------
-- 助手表: assistants
-- ----------------------------
DROP TABLE IF EXISTS `assistants`;
CREATE TABLE `assistants` (
    `id` VARCHAR(36) NOT NULL COMMENT '助手UUID',
    `name` VARCHAR(100) NOT NULL COMMENT '助手名',
    `description` TEXT DEFAULT NULL COMMENT '助手描述',
    `personality` TEXT DEFAULT NULL COMMENT '系统提示词',
    `voice` VARCHAR(50) DEFAULT NULL COMMENT '助手音色',
    `model_name` VARCHAR(64) DEFAULT NULL COMMENT 'LLM 模型名',
    `temperature` DECIMAL(2,1) DEFAULT NULL COMMENT '温度 0-2',
    `max_tokens` INT DEFAULT NULL COMMENT '最大输出 Token 数',
    `knowledge_ids` JSON DEFAULT NULL COMMENT '关联知识库ID列表（JSON 数组）',
    `user_id` VARCHAR(36) NOT NULL COMMENT '所属用户ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_assistant_user_id` (`user_id`),
    KEY `idx_assistant_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='助手表';

-- ----------------------------
-- 知识库表: knowledgebases
-- --------------------------
DROP TABLE IF EXISTS `knowledgebases`;
CREATE TABLE `knowledgebases` (
    `id` VARCHAR(36) NOT NULL COMMENT '知识库UUID',
    `name` VARCHAR(100) NOT NULL COMMENT '知识库名',
    `description` TEXT DEFAULT NULL COMMENT '知识库描述',
    `dataset_id` VARCHAR(64) DEFAULT NULL COMMENT 'RAGFlow 数据集ID（外部键）',
    `content` TEXT DEFAULT NULL COMMENT '知识库内容',
    `user_id` VARCHAR(36) NOT NULL COMMENT '所属用户ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_kb_user_dataset` (`user_id`, `dataset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';

-- ----------------------------
-- 会话表: sessions（会话维度持久化，替代按助手拉历史的临时方案）
-- --------------------------
DROP TABLE IF EXISTS `sessions`;
CREATE TABLE `sessions` (
    `id` VARCHAR(36) NOT NULL COMMENT '会话UUID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '归属用户ID',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '关联助手ID',
    `title` VARCHAR(100) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `is_pinned` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶 0:否 1:是',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_session_user_assistant` (`user_id`, `assistant_id`),
    KEY `idx_session_user_updated` (`user_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- ----------------------------
-- 聊天记录表: records
-- --------------------------
DROP TABLE IF EXISTS `records`;
CREATE TABLE `records` (
    `id` VARCHAR(36) NOT NULL COMMENT '聊天记录UUID',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '所属助手ID',
    `session_id` VARCHAR(36) DEFAULT NULL COMMENT '关联会话ID（文本会话维度）',
    `call_id` VARCHAR(36) DEFAULT NULL COMMENT '关联通话记录ID（语音消息时）',
    `role` TINYINT(1) NOT NULL COMMENT '角色 0:user, 1:assistant, 2:tool_call, 3:tool_result',
    `message` TEXT NOT NULL COMMENT '消息内容',
    `tool_name` VARCHAR(100) DEFAULT NULL COMMENT '工具名称（tool_call/tool_result 时）',
    `tool_args` JSON DEFAULT NULL COMMENT '工具参数（tool_call 时）',
    `tool_result` JSON DEFAULT NULL COMMENT '工具执行结果（tool_result 时）',
    `knowledgebase_info` JSON DEFAULT NULL COMMENT '引用的知识库信息 {docCount, docName[]}',
    `cost_time` BIGINT DEFAULT 0 COMMENT '响应耗时',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_cm_assistant_id` (`assistant_id`),
    KEY `idx_cm_session_id` (`session_id`),
    KEY `idx_cm_created_at` (`created_at`),
    KEY `idx_cm_assistant_created` (`assistant_id`, `created_at`),
    KEY `idx_cm_call_id` (`call_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天记录表';

-- ----------------------------
-- 通话记录表: call_records
-- ----------------------------
DROP TABLE IF EXISTS `call_records`;
CREATE TABLE `call_records` (
    `id` VARCHAR(36) NOT NULL COMMENT '通话记录UUID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '归属用户ID',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '助手ID',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态 0:失败 1:进行中 2:正常结束 3:中断',
    `duration_sec` INT DEFAULT 0 COMMENT '通话时长（秒）',
    `message_count` INT DEFAULT 0 COMMENT '消息数',
    `started_at` TIMESTAMP NULL COMMENT '开始时间',
    `ended_at` TIMESTAMP NULL COMMENT '结束时间',
    `fail_reason` VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    `recording_name` VARCHAR(255) DEFAULT NULL COMMENT '通话录音文件名（{callId}.webm），NULL 表示无录音',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_cr_user_time` (`user_id`, `started_at`),
    KEY `idx_cr_assistant_id` (`assistant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通话记录表';

-- ----------------------------
-- 审计日志表: audit_logs
-- ----------------------------
DROP TABLE IF EXISTS `audit_logs`;
CREATE TABLE `audit_logs` (
    `id` VARCHAR(36) NOT NULL COMMENT '审计日志UUID',
    `user_id` VARCHAR(36) DEFAULT NULL COMMENT '操作人用户ID（未登录时为 null）',
    `action` VARCHAR(64) NOT NULL COMMENT '操作动作（LOGIN/ASSISTANT_CREATE/...）',
    `target_type` VARCHAR(64) DEFAULT NULL COMMENT '目标类型',
    `target_id` VARCHAR(64) DEFAULT NULL COMMENT '目标ID',
    `detail` TEXT DEFAULT NULL COMMENT '详情（JSON）',
    `ip` VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
    `result` TINYINT(1) DEFAULT 1 COMMENT '结果 1:成功 0:失败',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_audit_user_time` (`user_id`, `created_at`),
    KEY `idx_audit_action_time` (`action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- ----------------------------
-- 数据归档表: records_archive（P2-9 数据归档与容量治理）
-- 与 records 结构对齐，行级复制 + archived_at 归档时间；不启用逻辑删除，is_deleted 仅存原值供追溯
-- ----------------------------
DROP TABLE IF EXISTS `records_archive`;
CREATE TABLE `records_archive` (
    `id` VARCHAR(36) NOT NULL COMMENT '聊天记录UUID（原值）',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '所属助手ID',
    `session_id` VARCHAR(36) DEFAULT NULL COMMENT '关联会话ID（文本会话维度）',
    `call_id` VARCHAR(36) DEFAULT NULL COMMENT '关联通话记录ID（语音消息时）',
    `role` TINYINT(1) NOT NULL COMMENT '角色 0:user, 1:assistant, 2:tool_call, 3:tool_result',
    `message` TEXT NOT NULL COMMENT '消息内容',
    `tool_name` VARCHAR(100) DEFAULT NULL COMMENT '工具名称（tool_call/tool_result 时）',
    `tool_args` JSON DEFAULT NULL COMMENT '工具参数（tool_call 时）',
    `tool_result` JSON DEFAULT NULL COMMENT '工具执行结果（tool_result 时）',
    `knowledgebase_info` JSON DEFAULT NULL COMMENT '引用的知识库信息 {docCount, docName[]}',
    `cost_time` BIGINT DEFAULT 0 COMMENT '响应耗时',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '消息原时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '来源 is_deleted 原值',
    `archived_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_archive_r_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天记录归档表';

-- ----------------------------
-- 数据归档表: call_records_archive（P2-9 数据归档与容量治理）
-- ----------------------------
DROP TABLE IF EXISTS `call_records_archive`;
CREATE TABLE `call_records_archive` (
    `id` VARCHAR(36) NOT NULL COMMENT '通话记录UUID（原值）',
    `user_id` VARCHAR(36) NOT NULL COMMENT '归属用户ID',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '助手ID',
    `status` TINYINT(1) DEFAULT 1 COMMENT '状态 0:失败 1:进行中 2:正常结束 3:中断',
    `duration_sec` INT DEFAULT 0 COMMENT '通话时长（秒）',
    `message_count` INT DEFAULT 0 COMMENT '消息数',
    `started_at` TIMESTAMP NULL COMMENT '开始时间',
    `ended_at` TIMESTAMP NULL COMMENT '结束时间',
    `fail_reason` VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    `recording_name` VARCHAR(255) DEFAULT NULL COMMENT '录音文件名（原值）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '来源 is_deleted 原值',
    `archived_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_archive_cr_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通话记录归档表';

-- ----------------------------
-- 数据归档表: audit_logs_archive（P2-9 数据归档与容量治理）
-- 审计日志源表无 is_deleted，天然无逻辑删除
-- ----------------------------
DROP TABLE IF EXISTS `audit_logs_archive`;
CREATE TABLE `audit_logs_archive` (
    `id` VARCHAR(36) NOT NULL COMMENT '审计日志UUID（原值）',
    `user_id` VARCHAR(36) DEFAULT NULL COMMENT '操作人用户ID（未登录时为 null）',
    `action` VARCHAR(64) NOT NULL COMMENT '操作动作（LOGIN/ASSISTANT_CREATE/...）',
    `target_type` VARCHAR(64) DEFAULT NULL COMMENT '目标类型',
    `target_id` VARCHAR(64) DEFAULT NULL COMMENT '目标ID',
    `detail` TEXT DEFAULT NULL COMMENT '详情（JSON）',
    `ip` VARCHAR(64) DEFAULT NULL COMMENT '客户端IP',
    `result` TINYINT(1) DEFAULT 1 COMMENT '结果 1:成功 0:失败',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `archived_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '归档时间',
    PRIMARY KEY (`id`),
    KEY `idx_archive_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志归档表';

-- ----------------------------
-- 组织表: orgs（P2-10 多租户与商业化前置）
-- ----------------------------
DROP TABLE IF EXISTS `orgs`;
CREATE TABLE `orgs` (
    `id` VARCHAR(36) NOT NULL COMMENT '组织UUID',
    `name` VARCHAR(64) NOT NULL COMMENT '组织名',
    `owner_user_id` VARCHAR(36) NOT NULL COMMENT '创建者(owner)用户ID',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '组织描述',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_org_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织表';

-- ----------------------------
-- 组织成员表: org_members（P2-10 多租户与商业化前置）
-- 角色: owner / editor / viewer，成员关系不可重复
-- --------------------------
DROP TABLE IF EXISTS `org_members`;
CREATE TABLE `org_members` (
    `id` VARCHAR(36) NOT NULL COMMENT '成员关系UUID',
    `org_id` VARCHAR(36) NOT NULL COMMENT '组织ID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '用户ID',
    `role` VARCHAR(16) NOT NULL COMMENT '角色 owner:拥有者 editor:编辑者 viewer:只读',
    `joined_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_org_user` (`org_id`, `user_id`),
    KEY `idx_member_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织成员表';

-- ----------------------------
-- 业务表增加组织的可选归属（P2-10 多租户与商业化前置）
-- org_id 为空表示个人数据（仍按 user_id 隔离）；非空表示组织数据（按组织角色矩阵控制）
-- ----------------------------
ALTER TABLE `assistants` ADD COLUMN `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）' AFTER `user_id`;
ALTER TABLE `assistants` ADD KEY `idx_assistant_org_id` (`org_id`);

ALTER TABLE `knowledgebases` ADD COLUMN `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）' AFTER `user_id`;
ALTER TABLE `knowledgebases` ADD KEY `idx_kb_org_id` (`org_id`);

ALTER TABLE `sessions` ADD COLUMN `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）' AFTER `assistant_id`;
ALTER TABLE `sessions` ADD KEY `idx_session_org_id` (`org_id`);

ALTER TABLE `call_records` ADD COLUMN `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）' AFTER `user_id`;
ALTER TABLE `call_records` ADD KEY `idx_cr_org_id` (`org_id`);

-- ----------------------------
-- 配额表: quotas（P2-10 用量配额与账单统计）
-- scope_type: org(组织级,优先) / user(用户级,无组织时兜底)；无记录时用环境变量默认值
-- ----------------------------
DROP TABLE IF EXISTS `quotas`;
CREATE TABLE `quotas` (
    `id` VARCHAR(36) NOT NULL COMMENT '配额UUID',
    `scope_type` VARCHAR(8) NOT NULL COMMENT '作用域类型 org:组织 user:用户',
    `scope_id` VARCHAR(36) NOT NULL COMMENT '作用域ID（org_id 或 user_id）',
    `assistant_limit` INT DEFAULT 50 COMMENT '助手上限',
    `daily_call_limit` INT DEFAULT 20 COMMENT '单日通话次数上限',
    `daily_call_sec_limit` BIGINT DEFAULT 3600 COMMENT '单日通话时长上限（秒）',
    `daily_msg_limit` INT DEFAULT 500 COMMENT '单日消息量上限',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quota_scope` (`scope_type`, `scope_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配额表';

-- ----------------------------
-- 第三方应用表: api_apps（P2-10 开放 OpenAPI）
-- app_key 为明文 API Key（第三方请求头 X-API-Key 携带），scopes 逗号分隔能力
-- ----------------------------
DROP TABLE IF EXISTS `api_apps`;
CREATE TABLE `api_apps` (
    `id` VARCHAR(36) NOT NULL COMMENT '应用UUID',
    `app_key` VARCHAR(64) NOT NULL COMMENT 'API Key（明文，第三方请求鉴权）',
    `app_name` VARCHAR(64) NOT NULL COMMENT '应用名称',
    `user_id` VARCHAR(36) NOT NULL COMMENT '属主用户ID',
    `scopes` VARCHAR(255) NOT NULL DEFAULT 'chat' COMMENT '能力范围，逗号分隔（chat:文本对话）',
    `webhook_url` VARCHAR(255) DEFAULT NULL COMMENT 'Webhook 回调地址（P2-17；空=不接收事件回调）',
    `webhook_secret` VARCHAR(64) DEFAULT NULL COMMENT 'Webhook 签名密钥（P2-17；用于 HMAC-SHA256 签名）',
    `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用 1:启用 0:停用',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_key` (`app_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方应用表';

-- ----------------------------
-- PSTN 外呼任务表: outbound_calls（P2-17 开放 OpenAPI 语音外呼）
-- 状态机: PENDING(待发起) → DIALING(呼叫中) → ACTIVE(接通) → COMPLETED(完成)；任一 → FAILED
-- ----------------------------
DROP TABLE IF EXISTS `outbound_calls`;
CREATE TABLE `outbound_calls` (
    `id` VARCHAR(36) NOT NULL COMMENT '外呼任务UUID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '属主用户ID（第三方应用属主）',
    `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '使用的助手ID',
    `phone_number` VARCHAR(32) NOT NULL COMMENT '被叫电话号码',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/DIALING/ACTIVE/COMPLETED/FAILED',
    `fail_reason` VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    `started_at` TIMESTAMP NULL COMMENT '发起时间',
    `completed_at` TIMESTAMP NULL COMMENT '完成时间',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_oc_user_time` (`user_id`, `created_at`),
    KEY `idx_oc_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='PSTN 外呼任务表';

-- ----------------------------
-- Webhook 投递记录表: webhook_deliveries（P2-17 Webhook 回调）
-- 异步投递 + 失败重试（指数退避，最多 app.webhook.retry-max-attempts 次）
-- ----------------------------
DROP TABLE IF EXISTS `webhook_deliveries`;
CREATE TABLE `webhook_deliveries` (
    `id` VARCHAR(36) NOT NULL COMMENT '投递记录UUID',
    `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型（call.connected/call.completed/call.status_changed/message.completed）',
    `app_id` VARCHAR(36) NOT NULL COMMENT '第三方应用ID',
    `payload` JSON DEFAULT NULL COMMENT '事件负载（JSON）',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/SUCCESS/FAILED',
    `attempt_count` INT DEFAULT 0 COMMENT '已尝试次数',
    `next_retry_at` TIMESTAMP NULL COMMENT '下次重试时间（失败且未超次时）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_wh_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Webhook 投递记录表';

-- ----------------------------
-- 插入用户数据
-- ----------------------------
INSERT INTO `users` (`id`, `username`, `nickname`, `password`, `avatar`, `email`, `phone`, `role`)
VALUES
('user_001', 'zhangsan', '张三', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', 'https://avatar.test.com/001.png', 'zhangsan@test.com', '13800138000', 'user'),
('user_002', 'lisi', '李四', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', 'https://avatar.test.com/002.png', 'lisi@test.com', '13800138001', 'user'),
('user_003', 'wangwu', '王五', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', NULL, 'wangwu@test.com', NULL, 'user'),
('user_admin', 'admin', '管理员', '$2a$10$LCqDIIEwadZOzoHvV5N2cuKcmuH6QSA0ug3obZRFn8iDGPpXstN.S', NULL, 'admin@test.com', NULL, 'admin');

-- ----------------------------
-- 插入助手数据
-- ----------------------------
INSERT INTO `assistants` (`id`, `name`, `description`, `personality`, `voice`, `user_id`)
VALUES
('assist_001', '撰写文案助手', '擅长文案创作、润色、公文撰写', '你是专业文案助手，语言简洁正式，逻辑清晰', 'female_01', 'user_001'),
('assist_002', '编程答疑助手', '专注Java、Python后端问题解答', '你是资深开发工程师，解答技术问题通俗易懂', 'male_01', 'user_001'),
('assist_003', '生活闲聊助手', '日常聊天、情感陪伴', '性格温和，语气亲切，耐心回应用户问题', 'female_02', 'user_002'),
('assist_004', '学习辅导助手', '学科知识点讲解、习题解答', '严谨认真，分步讲解知识点', 'male_02', 'user_003');

-- ----------------------------
-- 插入知识库数据
-- ----------------------------
INSERT INTO `knowledgebases` (`id`, `name`, `description`, `content`, `user_id`)
VALUES
('kb_001', '公文写作模板库', '常用通知、报告、总结模板合集', '1. 通知模板：标题+正文+落款\n2. 工作总结模板：工作内容+问题+计划', 'user_001'),
('kb_002', 'Java基础笔记', 'Java语法、面向对象、集合框架笔记', 'Java 面向对象三大特性：封装、继承、多态。集合分为单列集合与双列集合。', 'user_001'),
('kb_003', '日常美食菜谱', '家常菜简单做法大全', '番茄炒蛋：番茄切块，鸡蛋炒熟，混合翻炒加盐即可。', 'user_002'),
('kb_004', '高中数学公式', '常用数学公式汇总', '三角函数公式、数列公式、不等式公式整理', 'user_003');

-- ----------------------------
-- 插入记录数据
-- ----------------------------
INSERT INTO `records` (`id`, `assistant_id`, `role`, `message`, `cost_time`)
VALUES
-- 撰写文案助手 对话
('record_001', 'assist_001', 0, '帮我写一篇简短的工作通知', 0),
('record_002', 'assist_001', 1, '各位同事：本周周五下午三点召开部门例会，请准时参加。特此通知。', 680),
-- 编程答疑助手 对话
('record_003', 'assist_002', 0, 'Java List 和 Set 有什么区别？', 0),
('record_004', 'assist_002', 1, 'List 有序可重复，Set 无序不可重复。常见实现类分别为ArrayList、HashSet。', 520),
-- 生活闲聊助手 对话
('record_005', 'assist_003', 0, '有什么简单的家常菜推荐？', 0),
('record_006', 'assist_003', 1, '推荐番茄炒蛋、清炒时蔬，做法简单又美味。', 410),
-- 学习辅导助手 对话
('record_007', 'assist_004', 0, '三角函数基本公式有哪些？', 0),
('record_008', 'assist_004', 1, '正弦、余弦、正切基础关系：tanα = sinα / cosα，还有两角和差公式等。', 730);