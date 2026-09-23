-- ============================================================
-- 迁移辅助过程（由 scripts/db-migrate.sh 在应用迁移前创建，不属于业务 schema）
--
-- 为什么需要：MySQL 8 的 ALTER TABLE 不支持 IF NOT EXISTS（那是 MariaDB 的扩展），
-- 而"已部署库可能已经手工执行过某条 ALTER"是真实存在的情况（v2.28 手册 5.4 就要求过手工执行）。
-- 因此迁移脚本统一调这两个过程，它们先查 information_schema 再决定是否执行，使迁移可重复运行。
-- ============================================================

DROP PROCEDURE IF EXISTS `yunyu_add_column`;
DROP PROCEDURE IF EXISTS `yunyu_add_index`;

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

DELIMITER ;
