package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.mapper.CallRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 通话记录服务
 *
 * @author leyon
 */
@Service
public class CallRecordService {

    private final CallRecordMapper callRecordMapper;
    private final QuotaService quotaService;

    public CallRecordService(CallRecordMapper callRecordMapper, QuotaService quotaService) {
        this.callRecordMapper = callRecordMapper;
        this.quotaService = quotaService;
    }

    /**
     * 新增通话记录（创建前校验单日通话次数/时长配额，超限抛 403）
     */
    public CallRecord create(CallRecord record) {
        if (record.getUserId() != null) {
            quotaService.checkStartCall(record.getUserId());
        }
        callRecordMapper.insert(record);
        return record;
    }

    /**
     * 根据ID查询通话记录
     */
    public CallRecord getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return callRecordMapper.selectById(id);
    }

    /**
     * 更新通话记录
     */
    public boolean update(CallRecord record) {
        if (record == null || !StringUtils.hasText(record.getId())) {
            return false;
        }
        return callRecordMapper.updateById(record) > 0;
    }

    /**
     * 查询用户的通话记录（分页，按开始时间倒序）
     *
     * @param userId     用户ID
     * @param assistantId 助手ID（可空，按助手过滤）
     * @param offset     偏移量
     * @param limit      每页条数
     */
    public List<CallRecord> listByUser(String userId, String assistantId, long offset, long limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        LambdaQueryWrapper<CallRecord> wrapper = new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getUserId, userId)
                .eq(StringUtils.hasText(assistantId), CallRecord::getAssistantId, assistantId)
                .orderByDesc(CallRecord::getStartedAt)
                .last("LIMIT " + offset + ", " + limit);
        return callRecordMapper.selectList(wrapper);
    }

    /**
     * 统计用户的通话记录总数
     */
    public long countByUser(String userId, String assistantId) {
        if (!StringUtils.hasText(userId)) {
            return 0;
        }
        LambdaQueryWrapper<CallRecord> wrapper = new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getUserId, userId)
                .eq(StringUtils.hasText(assistantId), CallRecord::getAssistantId, assistantId);
        return callRecordMapper.selectCount(wrapper);
    }

    /**
     * 查询某时间之后的通话记录（用于用量统计，按开始时间正序）
     *
     * @param userId 用户ID
     * @param since  起始时间（含）
     */
    public List<CallRecord> listByUserSince(String userId, java.time.LocalDateTime since) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        LambdaQueryWrapper<CallRecord> wrapper = new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getUserId, userId)
                .ge(CallRecord::getStartedAt, since)
                .orderByAsc(CallRecord::getStartedAt);
        return callRecordMapper.selectList(wrapper);
    }
}
