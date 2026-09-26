package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天记录实体
 * 对应数据表：records
 *
 * @author leyon
 */
@TableName("records")
public class Record {

    /** 用于 knowledgebase_info JSON 字符串与对象互转 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // 角色常量
    /** 角色-用户 */
    public static final int ROLE_USER = 0;
    /** 角色-助手 */
    public static final int ROLE_ASSISTANT = 1;
    /** 角色-工具调用 */
    public static final int ROLE_TOOL_CALL = 2;
    /** 角色-工具执行结果 */
    public static final int ROLE_TOOL_RESULT = 3;

    // 逻辑删除常量
    /** 未删除 */
    public static final int NOT_DELETED = 0;
    /** 已删除 */
    public static final int DELETED = 1;

    /**
     * 主键ID（UUID）
     */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 所属助手ID
     */
    private String assistantId;

    /**
     * 关联会话ID（文本会话维度，语音消息可为空）
     */
    private String sessionId;

    /**
     * 关联通话记录ID（语音消息时）
     */
    private String callId;

    /**
     * 消息角色
     * 0 = 用户消息，1 = 助手回复消息
     */
    private Integer role;

    /**
     * 聊天消息内容
     */
    private String message;

    /**
     * 工具名称（role 为 tool_call / tool_result 时）
     */
    private String toolName;

    /**
     * 工具参数（role 为 tool_call 时，JSON 字符串）
     */
    private String toolArgs;

    /**
     * 工具执行结果（role 为 tool_result 时，JSON 字符串）
     */
    private String toolResult;

    /**
     * 引用的知识库信息（JSON 字符串列：{docCount, docName[], failed}）
     */
    private String knowledgebaseInfo;

    /**
     * AI 响应耗时(毫秒)，仅助手消息记录该字段
     */
    private Long costTime;

    /**
     * 创建时间，插入自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 逻辑删除标识
     * 0 = 正常，1 = 已删除
     */
    @TableLogic
    private Integer isDeleted;

    public Record() {
    }

    public Record(String id, String assistantId, Integer role, String message,
                  Long costTime, LocalDateTime createdAt, Integer isDeleted) {
        this.id = id;
        this.assistantId = assistantId;
        this.role = role;
        this.message = message;
        this.costTime = costTime;
        this.createdAt = createdAt;
        this.isDeleted = isDeleted;
    }

    // ========== Getter & Setter ==========
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAssistantId() {
        return assistantId;
    }

    public void setAssistantId(String assistantId) {
        this.assistantId = assistantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public Integer getRole() {
        return role;
    }

    public void setRole(Integer role) {
        this.role = role;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArgs() {
        return toolArgs;
    }

    public void setToolArgs(String toolArgs) {
        this.toolArgs = toolArgs;
    }

    public String getToolResult() {
        return toolResult;
    }

    public void setToolResult(String toolResult) {
        this.toolResult = toolResult;
    }

    /** MyBatis-Plus 映射 DB 与归档 SQL 使用（对外 JSON 隐藏，由 knowledgebase 对象承载） */
    @JsonIgnore
    public String getKnowledgebaseInfo() {
        return knowledgebaseInfo;
    }

    @JsonIgnore
    public void setKnowledgebaseInfo(String knowledgebaseInfo) {
        this.knowledgebaseInfo = knowledgebaseInfo;
    }

    /**
     * 知识库引用状态：与 query_end 帧的 knowledgebase 同名同形，
     * 历史回读（GET /api/sessions/{id}/messages）可直接复用前端的三态判定
     *
     * @param docCount 命中文档数
     * @param docName  命中文档名列表
     * @param failed   本轮检索是否因外部故障未得出结论
     */
    public record Knowledgebase(int docCount, List<String> docName, boolean failed) {
    }

    /**
     * JSON 对外暴露 knowledgebase 为对象；列缺失/空/脏数据一律返回 null，
     * 而不是臆造 {docCount:0, failed:false}——那会被渲染成"知识库确实没有相关内容"
     */
    @JsonProperty("knowledgebase")
    public Knowledgebase getKnowledgebase() {
        if (knowledgebaseInfo == null || knowledgebaseInfo.isBlank()) {
            return null;
        }
        try {
            return JSON_MAPPER.readValue(knowledgebaseInfo, new TypeReference<>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入列文本（形状与 {@link #getKnowledgebase()} 读取侧同源，落库与下发不会漂移） */
    public void setKnowledgebase(Knowledgebase knowledgebase) {
        try {
            this.knowledgebaseInfo = JSON_MAPPER.writeValueAsString(knowledgebase);
        } catch (Exception e) {
            this.knowledgebaseInfo = null;
        }
    }

    public Long getCostTime() {
        return costTime;
    }

    public void setCostTime(Long costTime) {
        this.costTime = costTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }

    @Override
    public String toString() {
        return "Record{" +
                "id='" + id + '\'' +
                ", assistantId='" + assistantId + '\'' +
                ", role=" + role +
                ", message='" + message + '\'' +
                ", costTime=" + costTime +
                ", createdAt=" + createdAt +
                ", isDeleted=" + isDeleted +
                '}';
    }
}