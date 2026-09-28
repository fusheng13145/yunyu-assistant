-- ----------------------------
-- 0007 knowledgebases.dataset_id 建唯一约束（v2.53 · C-119）
--
-- 迁移动机：`knowledgebases` 是 /api/ragflow 唯一的授权依据——转发用的是**一把共享 API Key**，
-- "这个 dataset_id 归谁"完全看这张表里有没有、是谁的一行。而读侧判定 findByDatasetId()
-- 一直是 `.eq(dataset_id).last("LIMIT 1")` 且**没有 ORDER BY**：代码早就假设"一个 dataset_id
-- 只有一行"，只是这个假设从没被库拦下来。v2.53 之前那个假设是可以被人打穿的——
-- POST /api/knowledges 接受调用方自带的 datasetId 并直接 insert，同一 id 能插出行数不限的行，
-- 于是"能不能访问别人的知识库"变成由存储顺序决定。
--
-- 为什么先断言再建索引：已部署的库里可能已经躺着历史重复行（那个接口写进去的）。
-- 直接 ADD UNIQUE 会 1062 中止，而报错看不出**该保留哪一行**——同一 dataset_id 上的两个
-- user_id 是两条互相冲突的所有权声明，机器不能替人裁决。所以按 0006 的同一口径，
-- 把"有重复就停下"做成硬前置，人工查证后再重跑本文件。
--
-- 幂等：yunyu_assert 只读不写；yunyu_add_unique_index 先查 information_schema.STATISTICS，
-- 索引已存在时是 no-op，重复执行安全。
--
-- NULL 行不受影响：dataset_id 可空（index.sql 的种子数据即为空），MySQL 的 UNIQUE 允许多行 NULL。
-- ----------------------------

-- 1) 硬前置：同一 dataset_id 出现两行即中止（含逻辑删除行——它们同样占住唯一键）
CALL yunyu_assert(
    (SELECT COUNT(*) FROM (SELECT dataset_id FROM knowledgebases
        WHERE dataset_id IS NOT NULL GROUP BY dataset_id HAVING COUNT(*) > 1) conflict) = 0,
    'knowledgebases 存在同一 dataset_id 的多行归属声明，需人工判定保留哪一行后再建 uk_kb_dataset_id');

-- 2) 唯一索引：把"一个数据集只有一份归属声明"从代码假设变成库约束
CALL yunyu_add_unique_index('knowledgebases', 'uk_kb_dataset_id', 'dataset_id');

-- 3) 落点核对（人工看输出；本查询不改数据）
SELECT (SELECT COUNT(*) FROM knowledgebases)                                  AS 登记行总数,
       (SELECT COUNT(*) FROM knowledgebases WHERE dataset_id IS NOT NULL)     AS 已有数据集ID,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'knowledgebases'
             AND INDEX_NAME = 'uk_kb_dataset_id')                              AS 唯一索引列数;
