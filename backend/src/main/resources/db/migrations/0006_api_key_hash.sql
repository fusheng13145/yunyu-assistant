-- ----------------------------
-- 0006 API Key 改为只存哈希，明文列下线（v2.45 · C-102）
--
-- 迁移动机：`app_key` 一直是**明文可检索列**。逻辑删除的应用行仍在库里、备份里、
-- DBA 的 SELECT 结果里，所以"吊销=删 Key"和"备份外泄=所有第三方凭据外泄"同时成立。
-- 现在存 SHA-256 hex：Java 侧（ApiKeyHasher）与 MySQL 侧（SHA2(x,256)）刻意同形
-- （小写 hex、64 字符），否则老 Key 会在上线瞬间全部失效。
--
-- 为什么明文列要删而不是留着：留一列明文，"哈希落库"就只是多存一列——备份里它照样在。
--
-- 部署顺序（不可颠倒）：**先跑本迁移，再上新代码**。
--   本迁移完成后旧 jar 立刻取不到 app_key 列，会直接报错——这是可接受的，
--   因为回退到 v2.44 及更早的 jar 需要先把明文恢复（不可能），所以删列前用 yunyu_assert
--   把"每行都有哈希"变成硬前置，而不是注释里的约定。
--
-- 幂等：每一步都有守卫。删列后重复执行本文件，依赖 app_key 的两条语句会被
-- yunyu_exec_if_column 跳过，其余步骤是 no-op。
--
-- 顺带登记的能力口径（C-100 口径 A）：存量应用回填为 chat,call,voice。
--   依据是 v2.45 之前的真实行为——scopes 在全仓没有任何读判点，任何有效 Key 都能打
--   chat / call / ws-voice 三条链路。不回填就等于在补判定的一瞬间把所有老 Key 的
--   外呼和语音能力收掉，那不是"收紧权限"，是单方面毁约。
-- ----------------------------

-- 1) 哈希列：先可空，回填收紧后改 NOT NULL
CALL yunyu_add_column('api_apps', 'app_key_hash',
    'VARCHAR(64) DEFAULT NULL COMMENT ''API Key 的 SHA-256 hex（小写）；明文不再落库''', 'app_key');

-- 2) 回填存量明文。WHERE 里带 app_key_hash IS NULL ⇒ 只填新列，重复跑不重算；
--    整条语句又被"app_key 列还在"守卫，故删列后重复执行本文件是 no-op。
CALL yunyu_exec_if_column('api_apps', 'app_key',
    'UPDATE api_apps SET app_key_hash = SHA2(app_key, 256) WHERE app_key_hash IS NULL');

-- 3) 硬前置：任何一行没有哈希就中止（宁可迁移失败，也不能删掉唯一的凭据来源）
CALL yunyu_assert(
    (SELECT COUNT(*) FROM api_apps WHERE app_key_hash IS NULL OR app_key_hash = '') = 0,
    'api_apps 存在未回填哈希的行，拒绝下线 app_key 明文列');

-- 4) 唯一约束从明文列换到哈希列（鉴权按它等值查库，成本与原来一致）
CALL yunyu_add_unique_index('api_apps', 'uk_app_key_hash', 'app_key_hash');
ALTER TABLE `api_apps` MODIFY COLUMN `app_key_hash` VARCHAR(64) NOT NULL
    COMMENT 'API Key 的 SHA-256 hex（小写）；明文不再落库';

-- 5) 存量应用能力口径 A：回填为三项全开（详见文件头）。
--    同样挂在"app_key 还在"的守卫下 ⇒ 这条只对存量行生效；上新代码后创建的应用默认只有 chat。
CALL yunyu_exec_if_column('api_apps', 'app_key',
    'UPDATE api_apps SET scopes = ''chat,call,voice'' WHERE scopes IS NULL OR scopes = '''' OR scopes = ''chat''');

-- 6) 明文列下线（引用它的 uk_app_key 由 MySQL 一并删除）
CALL yunyu_drop_column('api_apps', 'app_key');

-- 7) 落点核对（人工看输出；本查询不改数据）
SELECT COUNT(*)                                            AS 应用总数,
       SUM(app_key_hash IS NOT NULL)                       AS 已有哈希,
       SUM(scopes = 'chat,call,voice')                     AS 已回填能力,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'api_apps'
             AND COLUMN_NAME = 'app_key')                  AS 残留明文列
FROM api_apps;
