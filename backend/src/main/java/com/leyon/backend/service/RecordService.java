package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.Record;
import com.leyon.backend.mapper.RecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
     * 根据助手ID查询聊天记录，按创建时间正序
     * @param assistantId 助手ID
     * @return 聊天记录列表
     */
    public List<Record> listByAssistantId(String assistantId) {
        if (!StringUtils.hasText(assistantId)) {
            return List.of();
        }
        LambdaQueryWrapper<Record> queryWrapper = new LambdaQueryWrapper<Record>()
                .eq(Record::getAssistantId, assistantId)
                .orderByAsc(Record::getCreatedAt);
        return recordMapper.selectList(queryWrapper);
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
}