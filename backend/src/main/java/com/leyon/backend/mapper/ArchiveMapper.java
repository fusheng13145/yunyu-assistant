package com.leyon.backend.mapper;

import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据归档 Mapper（P2-9）
 * 全部使用手写原生 SQL：①规避实体 @TableLogic 导致的逻辑删除（物理删除源表）；
 * ②显式携带原 id，避免 @TableId ASSIGN_UUID 重新生成主键。
 *
 * @author leyon
 */
@Mapper
public interface ArchiveMapper {

    /**
     * 批量插入 records 归档（保留原列值）
     */
    @Insert("<script>"
            + "INSERT INTO records_archive (id, assistant_id, session_id, call_id, role, message, "
            + "tool_name, tool_args, tool_result, knowledgebase_info, cost_time, created_at, is_deleted) VALUES "
            + "<foreach collection='list' item='r' separator=','>"
            + "(#{r.id}, #{r.assistantId}, #{r.sessionId}, #{r.callId}, #{r.role}, #{r.message}, "
            + " #{r.toolName}, #{r.toolArgs}, #{r.toolResult}, #{r.knowledgebaseInfo}, #{r.costTime}, #{r.createdAt}, #{r.isDeleted})"
            + "</foreach></script>")
    int archiveRecords(@Param("list") List<Record> list);

    /**
     * 批量插入 call_records 归档（保留原列值）
     */
    @Insert("<script>"
            + "INSERT INTO call_records_archive (id, user_id, assistant_id, status, duration_sec, message_count, "
            + "started_at, ended_at, fail_reason, recording_name, created_at, is_deleted) VALUES "
            + "<foreach collection='list' item='c' separator=','>"
            + "(#{c.id}, #{c.userId}, #{c.assistantId}, #{c.status}, #{c.durationSec}, #{c.messageCount}, "
            + " #{c.startedAt}, #{c.endedAt}, #{c.failReason}, #{c.recordingName}, #{c.createdAt}, #{c.isDeleted})"
            + "</foreach></script>")
    int archiveCallRecords(@Param("list") List<CallRecord> list);

    /**
     * 批量插入 audit_logs 归档（保留原列值）
     */
    @Insert("<script>"
            + "INSERT INTO audit_logs_archive (id, user_id, action, target_type, target_id, detail, ip, result, created_at) VALUES "
            + "<foreach collection='list' item='a' separator=','>"
            + "(#{a.id}, #{a.userId}, #{a.action}, #{a.targetType}, #{a.targetId}, #{a.detail}, #{a.ip}, #{a.result}, #{a.createdAt})"
            + "</foreach></script>")
    int archiveAuditLogs(@Param("list") List<AuditLog> list);

    /**
     * 按主键物理删除 records 源表（手写 SQL 不追加逻辑删除条件）
     */
    @Delete("<script>DELETE FROM records WHERE id IN "
            + "<foreach collection='list' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteRecordsPhysical(@Param("list") List<String> ids);

    /**
     * 按主键物理删除 call_records 源表
     */
    @Delete("<script>DELETE FROM call_records WHERE id IN "
            + "<foreach collection='list' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteCallRecordsPhysical(@Param("list") List<String> ids);

    /**
     * 按主键物理删除 audit_logs 源表
     */
    @Delete("<script>DELETE FROM audit_logs WHERE id IN "
            + "<foreach collection='list' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteAuditLogsPhysical(@Param("list") List<String> ids);
}