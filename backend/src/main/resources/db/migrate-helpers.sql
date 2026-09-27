-- ============================================================
-- 迁移辅助过程（由 scripts/db-migrate.sh 在应用迁移前创建，不属于业务 schema）
--
-- 为什么需要：MySQL 8 的 ALTER TABLE 不支持 IF NOT EXISTS（那是 MariaDB 的扩展），
-- 而"已部署库可能已经手工执行过某条 ALTER"是真实存在的情况（v2.28 手册 5.4 就要求过手工执行）。
-- 因此迁移脚本统一调这些过程，它们先查 information_schema 再决定是否执行，使迁移可重复运行。
--
-- v2.45 随迁移 0006（API Key 明文列下线）补三个过程，因为"删列"这一类迁移有两个
-- 既有过程覆盖不到的需求：
--   ① 迁移中途要跑一条依赖旧列的 DML/DDL，而旧列删掉后同一文件可能再被跑一次
--      → yunyu_exec_if_column（列不在了就跳过，而不是报 Unknown column）；
--   ② 删列不可回退，"每行都已回填"必须是**硬前置**而不是注释里的约定
--      → yunyu_assert（不满足就 SIGNAL 中止，脚本 set -e 因此不会登记该迁移）；
--   ③ 既有 yunyu_add_index 只建普通 KEY，唯一约束没有落点
--      → yunyu_add_unique_index。
-- ============================================================

DROP PROCEDURE IF EXISTS `yunyu_add_column`;
DROP PROCEDURE IF EXISTS `yunyu_add_index`;
DROP PROCEDURE IF EXISTS `yunyu_add_unique_index`;
DROP PROCEDURE IF EXISTS `yunyu_drop_column`;
DROP PROCEDURE IF EXISTS `yunyu_exec_if_column`;
DROP PROCEDURE IF EXISTS `yunyu_assert`;

DELIMITER $$

CREATE PROCEDURE `yunyu_add_column`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT,
    IN p_after VARCHAR(64)
)
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = p_table
              AND COLUMN_NAME = p_column) = 0 THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        IF p_after IS NOT NULL AND p_after <> '' THEN
            SET @ddl = CONCAT(@ddl, ' AFTER `', p_after, '`');
        END IF;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE `yunyu_add_index`(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = p_table
              AND INDEX_NAME = p_index) = 0 THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD KEY `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE `yunyu_add_unique_index`(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_columns VARCHAR(255)
)
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = p_table
              AND INDEX_NAME = p_index) = 0 THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD UNIQUE KEY `', p_index, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE `yunyu_drop_column`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64)
)
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = p_table
              AND COLUMN_NAME = p_column) > 0 THEN
        -- 只发 DROP：MySQL 会自动删除引用该列的索引（如 uk_app_key），无需先手工删索引
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` DROP COLUMN `', p_column, '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE `yunyu_exec_if_column`(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_statement TEXT
)
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = p_table
              AND COLUMN_NAME = p_column) > 0 THEN
        SET @ddl = p_statement;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE `yunyu_assert`(
    IN p_ok TINYINT,
    IN p_message TEXT
)
BEGIN
    IF p_ok IS NULL OR p_ok = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = p_message;
    END IF;
END$$

DELIMITER ;
