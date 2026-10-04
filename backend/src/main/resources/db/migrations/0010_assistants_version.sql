-- ----------------------------
-- 0010 assistants 增加 version 乐观锁列（v2.79 · C-151，收口候选 ㊿）
--
-- 迁移动机：助手四个分节保存各自 PUT 整行，值全取自内存行、服务端无版本号——
-- 两个标签页同时打开同一助手的设置，后保存方会拿旧值覆盖先保存方的修改，且无任何提示。
-- v2.79 起写侧带版本号：实体 @Version + 乐观锁拦截器，UPDATE 带 WHERE version = ?，
-- 版本不匹配时更新 0 行 ⇒ 控制器返回"已被他人修改，请刷新后重试"。
--
-- 形状：NOT NULL DEFAULT 0，存量行从 0 起步；实体侧 MyBatis-Plus @Version 自增管理。
-- 服务端内部的部分更新（语音收尾回写人设等，仅 id+personality 的临时实体）version 为 NULL，
-- 乐观锁条件自动跳过——单写者场景无需版本仲裁，这是刻意口径。
-- 幂等：yunyu_add_column 先查 information_schema.COLUMNS，重复执行安全。
-- ----------------------------

CALL yunyu_add_column('assistants', 'version',
    'BIGINT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号（@Version 自增，PUT 携带用于多标签页冲突检测）''',
    'knowledge_ids');

SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'assistants'
              AND COLUMN_NAME = 'version')                      AS assistants_version列数;
