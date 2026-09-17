package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.Record;
import com.leyon.backend.mapper.RecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 聊天记录服务
 *
 * @author leyon
 */
@Service
public class RecordService {

    private final RecordMapper recordMapper;

    public RecordService(RecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    /**
     * 新增聊天记录
     * @param record 聊天记录实体
     * @return 保存后的记录对象
     */
    public Record add(Record record) {
        recordMapper.insert(record);
        return record;
    }

    /**
     * 根据会话ID分页加载聊天记录，只返回最近 N 条记录，避免全量加载导致内存溢出
     * 按创建时间倒序取 limit 条后再正序排列，保证返回的是最近的连续对话
     *
     * @param sessionId 会话ID
     * @param limit     最大加载数量
     * @return 聊天记录列表（按创建时间正序）
     */
    public List<Record> listBySessionIdLimit(String sessionId, int limit) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        int actualLimit = Math.max(limit, 1);
        LambdaQueryWrapper<Record> queryWrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getSessionId, sessionId)
                .orderByDesc(Record::getCreatedAt)
                .last("LIMIT " + actualLimit);
        List<Record> records = recordMapper.selectList(queryWrapper);
        Collections.reverse(records);
        return records;
    }

    /**
     * 根据助手ID查询聊天记录，按创建时间正序
     * @param assistantId 助手ID
     * @return 聊天记录列表
     */
    public List<Record> listByAssistantId(String assistantId) {
        return listByAssistantIdLimit(assistantId, Integer.MAX_VALUE);
    }

    /** 默认加载的最近聊天记录条数 */
    private static final int DEFAULT_LOAD_LIMIT = 50;

    /**
     * 根据助手ID分页加载聊天记录，只返回最近 N 条记录，避免全量加载导致内存溢出
     * 按创建时间倒序取 limit 条后再正序排列，保证返回的是最近的连续对话
     *
     * @param assistantId 助手ID
     * @param limit       最大加载数量
     * @return 聊天记录列表（按创建时间正序）
     */
    public List<Record> listByAssistantIdLimit(String assistantId, int limit) {
        if (!StringUtils.hasText(assistantId)) {
            return List.of();
        }
        int actualLimit = Math.max(limit, 1);
        // 先倒序取最近 N 条，再在内存中正序排列，确保返回最新的连续对话上下文
        LambdaQueryWrapper<Record> queryWrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getAssistantId, assistantId)
                .orderByDesc(Record::getCreatedAt)
                .last("LIMIT " + actualLimit);
        List<Record> records = recordMapper.selectList(queryWrapper);
        // 翻转为正序（最早的消息在前）
        Collections.reverse(records);
        return records;
    }

    /**
     * 根据助手ID批量删除聊天记录（逻辑删除）
     * @param assistantId 助手ID
     * @return 受影响行数
     */
    public int deleteByAssistantId(String assistantId) {
        if (!StringUtils.hasText(assistantId)) {
            return 0;
        }
        LambdaQueryWrapper<Record> queryWrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getAssistantId, assistantId);
        return recordMapper.delete(queryWrapper);
    }

    /**
     * 根据通话记录ID查询关联消息（按创建时间正序）
     * @param callId 通话记录ID
     * @return 关联消息列表
     */
    public List<Record> listByCallId(String callId) {
        if (!StringUtils.hasText(callId)) {
            return List.of();
        }
        LambdaQueryWrapper<Record> queryWrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getCallId, callId)
                .orderByAsc(Record::getCreatedAt);
        return recordMapper.selectList(queryWrapper);
    }
}