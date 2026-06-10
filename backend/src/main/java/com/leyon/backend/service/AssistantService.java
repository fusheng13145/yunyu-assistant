package com.leyon.backend.service;

import com.leyon.backend.entity.Assistant;
import com.leyon.backend.mapper.AssistantMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

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

    public List<Assistant> listAll() {
        return assistantMapper.selectAll();
    }

    public List<Assistant> listByUserId(String userId) {
        return assistantMapper.selectByUserId(userId);
    }

    public Assistant getById(String id) {
        return assistantMapper.selectById(id);
    }

    public boolean delete(String id) {
        return assistantMapper.deleteById(id) > 0;
    }

    public boolean update(Assistant assistant) {
        return assistantMapper.update(assistant) > 0;
    }
}
