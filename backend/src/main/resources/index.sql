-- ============================================================
-- 云谕助手 —— 全新环境建库脚本（仅新环境！）
--
-- ⚠️ 本文件会 DROP 整个库：已部署环境禁止执行，
--    请改用 scripts/db-migrate.sh 应用 db/migrations/ 下的增量脚本。
-- ⚠️ 本文件只建表、不灌数据；演示账号与示例对话已移到 db/seed-demo.sql（生产禁跑）。
--    此前演示账号（zhangsan/lisi/wangwu/admin）与明文可猜的 bcrypt 口令随建库进入生产，
--    等于给公网实例留了一批已知口令的账号，故 v2.29 起拆离。
--
-- 新环境初始化：
--   mysql -uroot -p < backend/src/main/resources/index.sql
--   （如要演示数据）mysql -uroot -p yunyu_assistant < backend/src/main/resources/db/seed-demo.sql
-- ============================================================

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
-- 注册邀请码表: invite_code（v2.37 邀请码制注册）
-- app.registration.mode=invite 时注册必须带一个未使用的码；领取由带 used_by IS NULL 条件的
-- 原子 UPDATE 完成（唯一键 uk_invite_code 是行锁落点），一个码只对应一个账号
-- ----------------------------
DROP TABLE IF EXISTS `invite_code`;
CREATE TABLE `invite_code` (
    `id` VARCHAR(36) NOT NULL COMMENT '邀请码UUID',
    `code` VARCHAR(32) NOT NULL COMMENT '码本体：大写字母+数字，排除易混字符 0/O/1/I/l（人工转发场景）',
    `created_by` VARCHAR(36) DEFAULT NULL COMMENT '生成者（管理员用户ID）',
    `used_by` VARCHAR(36) DEFAULT NULL COMMENT '使用者（注册成功的用户ID），NULL=未使用',
    `used_at` DATETIME DEFAULT NULL COMMENT '使用时间，NULL=未使用',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invite_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='注册邀请码表（一次性，原子领取）';

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
    `tools` VARCHAR(500) DEFAULT NULL COMMENT '可用工具白名单（JSON 数组，空=全部已注册工具，v2.28）',
    `user_id` VARCHAR(36) NOT NULL COMMENT '所属用户ID',
    `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_assistant_user_id` (`user_id`),
    KEY `idx_assistant_org_id` (`org_id`),
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
    `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_kb_user_dataset` (`user_id`, `dataset_id`),
    KEY `idx_kb_org_id` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';

-- ----------------------------
-- 会话表: sessions（会话维度持久化，替代按助手拉历史的临时方案）
-- --------------------------
DROP TABLE IF EXISTS `sessions`;
CREATE TABLE `sessions` (
    `id` VARCHAR(36) NOT NULL COMMENT '会话UUID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '归属用户ID',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '关联助手ID',
    `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）',
    `title` VARCHAR(100) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `is_pinned` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否置顶 0:否 1:是',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_session_user_assistant` (`user_id`, `assistant_id`),
    KEY `idx_session_user_updated` (`user_id`, `updated_at`),
    KEY `idx_session_org_id` (`org_id`)
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
    `knowledgebase_info` JSON DEFAULT NULL COMMENT '引用的知识库信息 {docCount, docName[], failed}，NULL=本轮未做检索',
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
    `org_id` VARCHAR(36) DEFAULT NULL COMMENT '所属组织ID（空=个人数据）',
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
    KEY `idx_cr_assistant_id` (`assistant_id`),
    KEY `idx_cr_org_id` (`org_id`)
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
    `knowledgebase_info` JSON DEFAULT NULL COMMENT '引用的知识库信息 {docCount, docName[], failed}，NULL=本轮未做检索',
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
-- 单日配额用量表: quota_daily_usage（v2.35 配额原子扣减账本）
-- 拦截判定读写的唯一落点：UPDATE ... SET used=used+1 WHERE ... AND used < limit
-- 与 quotas 不同：本表按日生长，不参与管理端编辑；展示口径仍从业务表数（手册 2.11）
-- ----------------------------
DROP TABLE IF EXISTS `quota_daily_usage`;
CREATE TABLE `quota_daily_usage` (
    `id` VARCHAR(36) NOT NULL COMMENT '用量行UUID',
    `scope_type` VARCHAR(8) NOT NULL COMMENT '作用域类型 org:组织 user:用户（与 quotas 同口径）',
    `scope_id` VARCHAR(36) NOT NULL COMMENT '作用域ID（org_id 或 user_id）',
    `usage_date` DATE NOT NULL COMMENT '统计日（按 JVM LocalDate.now() 的本地日切，与展示口径一致）',
    `metric` VARCHAR(32) NOT NULL COMMENT '指标：daily_msg(消息条数) / daily_call(通话次数)',
    `used` INT NOT NULL DEFAULT 0 COMMENT '当日已用量（仅由带余额条件的原子UPDATE推进）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_scope` (`scope_type`, `scope_id`, `usage_date`, `metric`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单日配额用量表（原子扣减账本）';

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
-- 迁移登记表: schema_migrations（v2.29 增量迁移机制）
-- scripts/db-migrate.sh 按文件名顺序应用 db/migrations/*.sql，并把已应用版本登记于此表
-- 新库由本文件建表后仍需跑一次 db-migrate.sh（迁移脚本自带存在性守卫，重复应用为 no-op）
-- ----------------------------
DROP TABLE IF EXISTS `schema_migrations`;
CREATE TABLE `schema_migrations` (
    `version` VARCHAR(128) NOT NULL COMMENT '迁移文件名（不含扩展名）',
    `applied_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '应用时间',
    PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已应用增量迁移登记表';

-- ============================================================
-- 表结构到此为止。此后的 schema 变更请新增 db/migrations/NNNN_简述.sql
-- （用 yunyu_add_column / yunyu_add_index 守卫，可重复执行），
-- 不要在本文件追加 ALTER：本文件只服务新环境，已部署库只能靠 migrations 演进。
-- ============================================================
