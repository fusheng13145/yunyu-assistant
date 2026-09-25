-- ----------------------------
-- 0003 单日配额用量表（v2.35 资金防线：配额从"先查后扣"改为原子扣减）
--
-- 用途：quota_daily_usage 是配额的**判定账本**——拦截时执行
--       UPDATE ... SET used = used + 1 WHERE ... AND used < #{limit}
--       检查与自增在同一条语句内（InnoDB 行锁），并发下不存在超发窗口。
--       旧写法（从 call_records / records 数当日行数再比较上限）在两个并发请求
--       同时读到"还差一条"时双双放行，且通话配额依赖"结束后才写入的记录"，
--       发起临界瞬间根本不计数——本表把判定时点搬回动作之前。
--
-- 唯一键 (scope_type, scope_id, usage_date, metric) 承担两个职责：
--   ① 每作用域/每日/每指标只允许一行，扣减才有唯一的行锁落点；
--   ② 多实例同时"建当日行"时让后来者撞 DuplicateKey，从而走 QuotaService 的
--      fail-safe 分支（按"上限内已被占用"处理，宁少放行不透支）。
--
-- 与展示口径的关系：管理端 aggregateUsage 仍从业务表数（见手册 2.11），
-- 与账本允许漂移——业务记录回滚/删除/归档后额度刻意不退还。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `quota_daily_usage` (
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
