-- ============================================================
-- 云谕助手 —— 演示种子数据（开发 / 演示专用，生产禁跑）
--
-- v2.29 起从 index.sql 拆出。此前建库脚本会连带写入 4 个演示账号
-- （zhangsan / lisi / wangwu / admin）+ 示例助手、知识库、对话记录。
-- 公网实例若用原脚本初始化，等于自带一批口令可猜的现成账号，
-- 且示例对话会污染用量统计与账单口径。
--
-- 执行：mysql -uroot -p yunyu_assistant < backend/src/main/resources/db/seed-demo.sql
-- 幂等：否（重复执行会主键冲突失败，属预期——演示数据只灌一次）
-- ============================================================
USE `yunyu_assistant`;

-- ----------------------------
-- 插入用户数据
-- ----------------------------
INSERT INTO `users` (`id`, `username`, `nickname`, `password`, `avatar`, `email`, `phone`, `role`)
VALUES
('user_001', 'zhangsan', '张三', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', 'https://avatar.test.com/001.png', 'zhangsan@test.com', '13800138000', 'user'),
('user_002', 'lisi', '李四', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', 'https://avatar.test.com/002.png', 'lisi@test.com', '13800138001', 'user'),
('user_003', 'wangwu', '王五', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOsWWIrbu2gXT9OmdE6OmgNFj/bq', NULL, 'wangwu@test.com', NULL, 'user'),
('user_admin', 'admin', '管理员', '$2a$10$LCqDIIEwadZOzoHvV5N2cuKcmuH6QSA0ug3obZRFn8iDGPpXstN.S', NULL, 'admin@test.com', NULL, 'admin');

-- ----------------------------
-- 插入助手数据
-- ----------------------------
INSERT INTO `assistants` (`id`, `name`, `description`, `personality`, `voice`, `user_id`)
VALUES
('assist_001', '撰写文案助手', '擅长文案创作、润色、公文撰写', '你是专业文案助手，语言简洁正式，逻辑清晰', 'female_01', 'user_001'),
('assist_002', '编程答疑助手', '专注Java、Python后端问题解答', '你是资深开发工程师，解答技术问题通俗易懂', 'male_01', 'user_001'),
('assist_003', '生活闲聊助手', '日常聊天、情感陪伴', '性格温和，语气亲切，耐心回应用户问题', 'female_02', 'user_002'),
('assist_004', '学习辅导助手', '学科知识点讲解、习题解答', '严谨认真，分步讲解知识点', 'male_02', 'user_003');

-- ----------------------------
-- 插入知识库数据
-- ----------------------------
INSERT INTO `knowledgebases` (`id`, `name`, `description`, `content`, `user_id`)
VALUES
('kb_001', '公文写作模板库', '常用通知、报告、总结模板合集', '1. 通知模板：标题+正文+落款\n2. 工作总结模板：工作内容+问题+计划', 'user_001'),
('kb_002', 'Java基础笔记', 'Java语法、面向对象、集合框架笔记', 'Java 面向对象三大特性：封装、继承、多态。集合分为单列集合与双列集合。', 'user_001'),
('kb_003', '日常美食菜谱', '家常菜简单做法大全', '番茄炒蛋：番茄切块，鸡蛋炒熟，混合翻炒加盐即可。', 'user_002'),
('kb_004', '高中数学公式', '常用数学公式汇总', '三角函数公式、数列公式、不等式公式整理', 'user_003');

-- ----------------------------
-- 插入记录数据
-- ----------------------------
INSERT INTO `records` (`id`, `assistant_id`, `role`, `message`, `cost_time`)
VALUES
-- 撰写文案助手 对话
('record_001', 'assist_001', 0, '帮我写一篇简短的工作通知', 0),
('record_002', 'assist_001', 1, '各位同事：本周周五下午三点召开部门例会，请准时参加。特此通知。', 680),
-- 编程答疑助手 对话
('record_003', 'assist_002', 0, 'Java List 和 Set 有什么区别？', 0),
('record_004', 'assist_002', 1, 'List 有序可重复，Set 无序不可重复。常见实现类分别为ArrayList、HashSet。', 520),
-- 生活闲聊助手 对话
('record_005', 'assist_003', 0, '有什么简单的家常菜推荐？', 0),
('record_006', 'assist_003', 1, '推荐番茄炒蛋、清炒时蔬，做法简单又美味。', 410),
-- 学习辅导助手 对话
('record_007', 'assist_004', 0, '三角函数基本公式有哪些？', 0),
('record_008', 'assist_004', 1, '正弦、余弦、正切基础关系：tanα = sinα / cosα，还有两角和差公式等。', 730);

