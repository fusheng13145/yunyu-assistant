-- ----------------------------
-- 0004 注册邀请码表（v2.37 邀请码制注册：上线决策「注册先邀请码，条件成熟后放开」的第一阶段闸门）
--
-- 用途：app.registration.mode=invite（默认）时，注册必须携带一个未被使用的码。
--       领取时执行 UPDATE ... SET used_by=?, used_at=NOW() WHERE code=? AND used_by IS NULL
--       判"未使用"与写入使用者在同一条语句内（InnoDB 行锁 + 唯一索引落点），
--       并发下同一码只有一个注册者能拿到 1 行影响；"先查未使用再更新"会让一个码注册进多人。
--
-- 唯一键 uk_invite_code 承担两个职责：
--   ① 码值全局唯一 ⇒ 领取语句有唯一的行锁落点；
--   ② 管理端批量生成撞码时由 DB 拒绝，而不是静默发出两个可用码。
--
-- 与账号生命周期的关系：used_by 记录使用者；账号被逻辑删除后此处仍留痕（与 quota_daily_usage
-- 同一口径——刻意不回收、不退还，避免"删号即可复用码"变成注册漏斗）。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `invite_code` (
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
