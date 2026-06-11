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
    `user_id` VARCHAR(36) NOT NULL COMMENT '所属用户ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='助手表';

-- ----------------------------
-- 知识库表: knowledgebases
---- --------------------------
DROP TABLE IF EXISTS `knowledgebases`;
CREATE TABLE `knowledgebases` (
    `id` VARCHAR(36) NOT NULL COMMENT '知识库UUID',
    `name` VARCHAR(100) NOT NULL COMMENT '知识库名',
    `description` TEXT DEFAULT NULL COMMENT '知识库描述',
    `content` TEXT DEFAULT NULL COMMENT '知识库内容',
    `user_id` VARCHAR(36) NOT NULL COMMENT '所属用户ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';

-- ----------------------------
-- 聊天记录表: records
---- --------------------------
DROP TABLE IF EXISTS `records`;
CREATE TABLE `records` (
    `id` VARCHAR(36) NOT NULL COMMENT '聊天记录UUID',
    `assistant_id` VARCHAR(36) NOT NULL COMMENT '所属助手ID',
    `role` TINYINT(1) NOT NULL COMMENT '角色 0:user, 1:assistant',
    `message` TEXT NOT NULL COMMENT '消息内容',
    `cost_time` BIGINT DEFAULT 0 COMMENT '响应耗时',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '消息时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天记录表';

-- ----------------------------
-- 插入用户数据
-- ----------------------------
INSERT INTO `users` (`id`, `username`, `nickname`, `password`, `avatar`, `email`, `phone`)
VALUES
('user_001', 'zhangsan', '张三', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', 'https://avatar.test.com/001.png', 'zhangsan@test.com', '13800138000'),
('user_002', 'lisi', '李四', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', 'https://avatar.test.com/002.png', 'lisi@test.com', '13800138001'),
('user_003', 'wangwu', '王五', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', NULL, 'wangwu@test.com', NULL);

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