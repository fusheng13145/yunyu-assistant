-- ----------------------------
-- 0011 knowledgebases 删除 content 死列（v2.81 · C-157，收口候选 ㊴）
--
-- 迁移动机：旧"本地文本片段"形状的遗迹。content 自建表起全仓零写入零读取
-- （v2.53 实测断言过：本地行 name/description 只在创建时同步一次，content 连创建时也无写入），
-- 死列留着只会让后来者误以为"本地片段检索"还活着。name/description 保留（创建时同步写入、
-- v2.81 起随上游改名对账更新）。
--
-- 删列属不可逆迁移：列内数据（历史手工行若有）将无法恢复。跑前确认：
--   SELECT COUNT(*) FROM knowledgebases WHERE content IS NOT NULL;
-- 开发库实测 4 行非空——来源是 seed-demo.sql 的种子行（INSERT 显式写入 content），
-- 非 v2.53 时代写入路径；4 行值随删列丢弃（该列全仓零读取，功能性无损）。
-- 首版登记误写"0 行非空"，由应用迁移后的复核抓出并在此更正（4.8 教训：读数必须跑在真库上）。幂等：yunyu_drop_column 先查列存在。
-- ----------------------------

CALL yunyu_drop_column('knowledgebases', 'content');

SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledgebases'
              AND COLUMN_NAME = 'content')                       AS content列残留数;
