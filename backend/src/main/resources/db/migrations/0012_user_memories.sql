-- ----------------------------
-- 0012 新建 user_memories 用户长期记忆表（v2.85 · C-161，收口候选 ⑫）
--
-- 设计口径（对齐豆包 FAQ 的"可查看/删除"语义）：
--   记忆按用户维度存储（不含助手/会话归属——长期记忆跟人走，不跟助手走）；
--   写入路径只有 save_memory 工具（LLM 显式保存，先规则化后模型化）；
--   会话注入在装配时拼进系统提示（只读，不参与配额）；
--   用户可见可删（前端入口），删除为逻辑删除（@TableLogic）。
-- ----------------------------

CREATE TABLE `user_memories` (
    `id` VARCHAR(36) NOT NULL COMMENT '记忆UUID',
    `user_id` VARCHAR(36) NOT NULL COMMENT '所属用户ID',
    `content` VARCHAR(500) NOT NULL COMMENT '记忆内容（一句话）',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否删除 0:未删除, 1:已删除',
    PRIMARY KEY (`id`),
    KEY `idx_mem_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户长期记忆表';

SELECT COUNT(*) AS table_created FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_memories';
