package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.Record;
import com.leyon.backend.mapper.RecordMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RecordService {

    private final RecordMapper recordMapper;

    public RecordService(RecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    /** 新增一条聊天记录 */
    public Record add(Record record) {
        if (record.getId() == null) {
            record.setId(UUID.randomUUID().toString());
        }
        recordMapper.insert(record);
        return record;
    }

    /** 查询某助手的所有聊天记录（按时间正序） */
    public List<Record> listByAssistantId(String assistantId) {
        return recordMapper.selectList(
            new LambdaQueryWrapper<Record>()
                .eq(Record::getAssistantId, assistantId)
                .orderByAsc(Record::getCreatedAt)
        );
    }

    /** 删除某助手的所有记录 */
    public int deleteByAssistantId(String assistantId) {
        return recordMapper.delete(
            new LambdaQueryWrapper<Record>()
                .eq(Record::getAssistantId, assistantId)
        );
    }
}
