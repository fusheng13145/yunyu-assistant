-- ----------------------------
-- 0008 records / records_archive 增加 fail_reason 列（v2.73 · C-143，收口候选 S-22）
--
-- 迁移动机：失败的对话回合此前**整轮不落库**（S-22）——模型流失败路径只发 error 帧，
-- saveConversation 只挂在成功/取消/迭代上限三条路径上，于是"用户说了什么、模型已经吐出了
-- 多少"一起消失，事后无从分辨"没问到"与"问了但没答上"。v2.73 起失败回合随错误路径落库：
-- user 行 + 已生成的部分正文（assistant 行），失败原因以**脱敏类别文案**落在本列
-- （不含异常原文——异常串可能带 Key 与内网地址，与 WS error 帧同一脱敏口径）。
--
-- 形状：可空 VARCHAR(255)，存量行不回填（NULL＝该轮不是失败轮，与"语义上失败但没记"相容）；
-- 归档表同列同步（ArchiveMapper 的 INSERT 显式列清单必须带上它）。
--
-- 幂等：yunyu_add_column 先查 information_schema.COLUMNS，列已存在时是 no-op，重复执行安全。
-- ----------------------------

CALL yunyu_add_column('records', 'fail_reason',
    'VARCHAR(255) DEFAULT NULL COMMENT ''失败原因（类别文案，不含异常原文）：本轮回复合失败时随已生成部分落库''',
    'knowledgebase_info');

CALL yunyu_add_column('records_archive', 'fail_reason',
    'VARCHAR(255) DEFAULT NULL COMMENT ''失败原因（类别文案，不含异常原文）：本轮回复合失败时随已生成部分落库''',
    'knowledgebase_info');

-- 落点核对（人工看输出；本查询不改数据）
SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records'
              AND COLUMN_NAME = 'fail_reason')         AS records_fail_reason列数,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'records_archive'
              AND COLUMN_NAME = 'fail_reason')         AS archive_fail_reason列数;
