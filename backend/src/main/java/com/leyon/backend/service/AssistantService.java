package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.mapper.AssistantMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AssistantService {

    private final AssistantMapper assistantMapper;

    public AssistantService(AssistantMapper assistantMapper) {
        this.assistantMapper = assistantMapper;
    }

    public Assistant create(Assistant assistant) {
        assistant.setId(UUID.randomUUID().toString());
        assistantMapper.insert(assistant);
        return assistant;
    }

    public List<Assistant> listByUserId(String userId) {
        return assistantMapper.selectList(
            new LambdaQueryWrapper<Assistant>()
                .eq(Assistant::getUserId, userId)
                .orderByDesc(Assistant::getCreatedAt)
        );
    }

    public Assistant getById(String id) {
        return assistantMapper.selectById(id);
    }

    public boolean delete(String id) {
        return assistantMapper.deleteById(id) > 0;
    }

    public boolean update(Assistant assistant) {
        return assistantMapper.updateById(assistant) > 0;
    }
}
