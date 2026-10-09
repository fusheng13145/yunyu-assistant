-- ----------------------------
-- 0013 多标识登录的账号唯一性（v2.89 · C-170/C-171 配套 schema）
--
-- 用途：登录从"只按 username 查"变成"用户名 / 邮箱 / 手机号三态路由"后，邮箱与手机号
--       第一次成了**登录凭据**，此前它们只是可选的资料列（既无索引也无唯一约束）。
--       两张表式风险必须在这里收口：
--       ① 两行活账号共用一个邮箱 ⇒ 登录时"挑哪一行"决定谁能进门；
--       ② `WHERE email = ?` 无索引 ⇒ 每次邮箱登录全表扫。
--
-- 为什么用生成列而不是直接给 email 加 UNIQUE：users 是逻辑删除（`is_deleted`），
-- 普通唯一索引会把已删除账号的邮箱也占住 —— 那正是 S-16 在 username 上从没解决过的形状
-- （注销过的用户永远注册不回来）。生成列把"仅活行参与"写进表达式本身，行删了表达式变 NULL，
-- 而 NULL 在唯一索引里互不冲突，槽位自动释放。
--
-- 空串与 NULL 同视（`email <> ''`）：库里历史行可能写过空串，若原样参与唯一，
-- 第二个"没填邮箱"的人注册就会被 1062 撞死，而报错指向邮箱。
--
-- 兜底查的是普通列（`WHERE email = ?`），因此**本迁移没跑也不会 500**，代价只是这两条
-- 硬保证（见手册 5.4）：唯一性退化为服务层查重（竞态下可能出双行），索引退化为全表扫。
-- 上线顺序仍是"先迁移、再上新代码"。
--
-- 前置断言：库里已有活账号共用邮箱/手机号时，本迁移**中止而不自动改数据**——
--   哪一行的邮箱该改、要不要合并账号，只有运维知道；脚本 set -e 因此不会登记该迁移，
--   人工处置后重跑即可（重复运行是 no-op）。
--
-- 幂等：yunyu_add_column / yunyu_add_index / yunyu_add_unique_index 先查
--   information_schema 再决定是否执行。
-- ----------------------------

SET @dup_email = (SELECT COUNT(*) FROM (
    SELECT LOWER(`email`) AS e FROM `users`
    WHERE IFNULL(`is_deleted`, 0) = 0 AND `email` IS NOT NULL AND `email` <> ''
    GROUP BY LOWER(`email`) HAVING COUNT(*) > 1) d);
CALL yunyu_assert(@dup_email = 0,
    '0013 前置检查：存在多个活账号共用同一邮箱，请人工处置（改邮箱或注销其一）后重跑本迁移；迁移不会自动删改数据');

SET @dup_phone = (SELECT COUNT(*) FROM (
    SELECT `phone` AS p FROM `users`
    WHERE IFNULL(`is_deleted`, 0) = 0 AND `phone` IS NOT NULL AND `phone` <> ''
    GROUP BY `phone` HAVING COUNT(*) > 1) d);
CALL yunyu_assert(@dup_phone = 0,
    '0013 前置检查：存在多个活账号共用同一手机号，请人工处置后重跑本迁移；迁移不会自动删改数据');

CALL yunyu_add_column('users', 'email_active',
    'VARCHAR(100) GENERATED ALWAYS AS (IF(IFNULL(`is_deleted`,0) = 0 AND `email` IS NOT NULL AND `email` <> '''', LOWER(`email`), NULL)) VIRTUAL', 'email');
CALL yunyu_add_unique_index('users', 'uk_users_email_active', '`email_active`');
CALL yunyu_add_index('users', 'idx_users_email', '`email`');

CALL yunyu_add_column('users', 'phone_active',
    'VARCHAR(20) GENERATED ALWAYS AS (IF(IFNULL(`is_deleted`,0) = 0 AND `phone` IS NOT NULL AND `phone` <> '''', `phone`, NULL)) VIRTUAL', 'phone');
CALL yunyu_add_unique_index('users', 'uk_users_phone_active', '`phone_active`');
CALL yunyu_add_index('users', 'idx_users_phone', '`phone`');
