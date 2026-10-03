-- ----------------------------
-- 0009 call_records / call_records_archive 增加 recording_fail_reason 列（v2.78 · C-150，⑳ 告警面 + ㉝ 一半）
--
-- 迁移动机：录音上传此前是数据面黑盒——保存失败只回调用方一句"保存录音失败"并打一行日志，
-- call_records.recording_name 保持 NULL，与"这通电话本来就没录"完全同形，
-- 事后无法回答"有多少通电话的录音没存下来、为什么"。v2.78 起失败写入本列（脱敏类别文案），
-- 管理端概览新增 recordingFailCount 计数，录音失败从此进入告警面。
--
-- 形状：可空 VARCHAR(255)，存量行不回填（NULL＝该通电话没有录音失败记录）。
-- 幂等：yunyu_add_column 先查 information_schema.COLUMNS，重复执行安全。
-- ----------------------------

CALL yunyu_add_column('call_records', 'recording_fail_reason',
    'VARCHAR(255) DEFAULT NULL COMMENT ''录音保存失败原因（类别文案）：上传落盘失败时写入，NULL=无失败''',
    'recording_name');

CALL yunyu_add_column('call_records_archive', 'recording_fail_reason',
    'VARCHAR(255) DEFAULT NULL COMMENT ''录音保存失败原因（类别文案）：上传落盘失败时写入，NULL=无失败''',
    'recording_name');

SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'call_records'
              AND COLUMN_NAME = 'recording_fail_reason')          AS call_records列数,
       (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'call_records_archive'
              AND COLUMN_NAME = 'recording_fail_reason')          AS archive列数;
