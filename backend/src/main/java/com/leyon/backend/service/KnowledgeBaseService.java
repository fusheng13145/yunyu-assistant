package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识库业务服务
 *
 * @author leyon
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    /**
     * 创建知识库
     * @param kb 知识库实体
     * @return 保存后的知识库对象
     */
    public KnowledgeBase create(KnowledgeBase kb) {
        // 实体已配置 ASSIGN_UUID，MP 自动生成主键，移除手动 UUID 逻辑
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    /**
     * 根据用户ID查询所属知识库列表，按创建时间倒序
     * @param userId 用户ID
     * @return 知识库集合
     */
    public List<KnowledgeBase> listByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        LambdaQueryWrapper<KnowledgeBase> queryWrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .orderByDesc(KnowledgeBase::getCreatedAt);
        return knowledgeBaseMapper.selectList(queryWrapper);
    }

    /**
     * 查询全部知识库数据
     * @return 知识库集合
     */
    public List<KnowledgeBase> listAll() {
        return knowledgeBaseMapper.selectList(null);
    }

    /**
     * 根据主键ID查询单条知识库
     * @param id 知识库ID
     * @return 知识库实体，不存在返回 null
     */
    public KnowledgeBase getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return knowledgeBaseMapper.selectById(id);
    }

    /**
     * 校验指定数据集是否属于当前用户（对象级授权，防越权）
     * @param datasetId RAGFlow 数据集ID
     * @param userId    登录用户ID
     * @return 属于当前用户返回 true，否则 false
     */
    public boolean isOwnedDataset(String datasetId, String userId) {
        if (!StringUtils.hasText(datasetId) || !StringUtils.hasText(userId)) {
            return false;
        }
        LambdaQueryWrapper<KnowledgeBase> queryWrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDatasetId, datasetId)
                .eq(KnowledgeBase::getUserId, userId);
        return knowledgeBaseMapper.selectCount(queryWrapper) > 0;
    }

    /**
     * 根据ID删除知识库（逻辑删除）
     * @param id 知识库ID
     * @return true-删除成功 false-删除失败
     */
    public boolean delete(String id) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        LambdaUpdateWrapper<KnowledgeBase> updateWrapper = new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id);
        return knowledgeBaseMapper.delete(updateWrapper) > 0;
    }

    /**
     * 更新知识库信息（根据主键更新）
     * @param kb 待更新实体（必须包含主键ID）
     * @return true-更新成功 false-更新失败
     */
    public boolean update(KnowledgeBase kb) {
        if (kb == null || !StringUtils.hasText(kb.getId())) {
            return false;
        }
        return knowledgeBaseMapper.updateById(kb) > 0;
    }
}