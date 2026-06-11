package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    public KnowledgeBase create(KnowledgeBase kb) {
        if (kb.getId() == null) {
            kb.setId(UUID.randomUUID().toString());
        }
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    public List<KnowledgeBase> listByUserId(String userId) {
        return knowledgeBaseMapper.selectList(
            new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .orderByDesc(KnowledgeBase::getCreatedAt)
        );
    }

    public List<KnowledgeBase> listAll() {
        return knowledgeBaseMapper.selectList(null);
    }

    public KnowledgeBase getById(String id) {
        return knowledgeBaseMapper.selectById(id);
    }

    public boolean delete(String id) {
        return knowledgeBaseMapper.delete(
                new LambdaUpdateWrapper<KnowledgeBase>().eq(KnowledgeBase::getId, id)
        ) > 0;
    }

    public boolean update(KnowledgeBase kb) {
        return knowledgeBaseMapper.updateById(kb) > 0;
    }
}
