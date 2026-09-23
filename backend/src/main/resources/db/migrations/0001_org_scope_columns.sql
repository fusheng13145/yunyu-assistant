-- ----------------------------
-- 0001 业务表增加组织的可选归属（P2-10 多租户与商业化前置）
-- org_id 为空表示个人数据（仍按 user_id 隔离）；非空表示组织数据（按组织角色矩阵控制）
--
-- 幂等：yunyu_add_column / yunyu_add_index 先查 information_schema 再决定是否执行，
-- 已手工执行过这些 ALTER 的库重复跑本文件是 no-op。
-- 本文件对应 v2.14 的变更，从 index.sql 内联 ALTER 迁出（v2.29 起 index.sql 只建表、不再带 ALTER）。
-- ----------------------------

CALL yunyu_add_column('assistants', 'org_id', 'VARCHAR(36) DEFAULT NULL COMMENT ''所属组织ID（空=个人数据）''', 'user_id');
CALL yunyu_add_index('assistants', 'idx_assistant_org_id', '`org_id`');

CALL yunyu_add_column('knowledgebases', 'org_id', 'VARCHAR(36) DEFAULT NULL COMMENT ''所属组织ID（空=个人数据）''', 'user_id');
CALL yunyu_add_index('knowledgebases', 'idx_kb_org_id', '`org_id`');

CALL yunyu_add_column('sessions', 'org_id', 'VARCHAR(36) DEFAULT NULL COMMENT ''所属组织ID（空=个人数据）''', 'assistant_id');
CALL yunyu_add_index('sessions', 'idx_session_org_id', '`org_id`');

CALL yunyu_add_column('call_records', 'org_id', 'VARCHAR(36) DEFAULT NULL COMMENT ''所属组织ID（空=个人数据）''', 'user_id');
CALL yunyu_add_index('call_records', 'idx_cr_org_id', '`org_id`');
