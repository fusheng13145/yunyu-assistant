-- ----------------------------
-- 0005 账号凭据版本（v2.42 · C-93 会话凭据失效收口）
--
-- 用途：给"作废某个账号已签发的全部令牌"一个落点。
--       此前签发的 JWT 只要签名正确就一直有效（access 默认数十小时、refresh 7 天），
--       而改密走的是整行 update、不触碰任何令牌，refresh 又不校验密码——
--       所以"改密踢下线"从来没有实现过，被盗的 refresh 令牌还能靠轮换无限续命。
--       现在令牌的 tv claim 必须等于本列，改密时两者在同一条 UPDATE 里推进。
--
-- 默认 0 的含义：v2.42 之前签发的存量令牌没有 tv claim，按版本 0 处理，
-- 因此应用本迁移本身不会把在线用户踢下线；一旦该账号改密（→1）旧令牌全部失效。
--
-- 幂等：yunyu_add_column 先查 information_schema 再决定是否执行，重复跑是 no-op。
-- ----------------------------

CALL yunyu_add_column('users', 'token_version', 'INT NOT NULL DEFAULT 0 COMMENT ''凭据版本：改密即+1，令牌 tv claim 与之不符立刻失效''', 'role');
