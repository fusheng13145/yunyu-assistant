-- ----------------------------
-- 0002 助手可用工具白名单（v2.28 按助手裁剪 AI 能力）
-- 存 JSON 数组字符串（如 ["get_weather","hangup"]）；
-- NULL / 空数组 = 全部已注册工具可用（既有助手零迁移即行为不变）
--
-- 幂等：已手工执行过该 ALTER 的库（v2.28 手册 5.4 曾要求手工执行）重复跑本文件是 no-op。
-- ----------------------------

CALL yunyu_add_column('assistants', 'tools', 'VARCHAR(500) DEFAULT NULL COMMENT ''可用工具白名单（JSON 数组，空=全部已注册工具）''', 'knowledge_ids');
