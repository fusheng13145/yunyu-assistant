package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.mapper.AssistantMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;

/**
 * 助手业务服务
 *
 * @author leyon
 */
@Service
public class AssistantService {

    private final AssistantMapper assistantMapper;

    public AssistantService(AssistantMapper assistantMapper) {
        this.assistantMapper = assistantMapper;
    }

    /**
     * 创建助手信息
     * @param assistant 助手实体
     * @return 保存后的助手对象
     */
    public Assistant create(Assistant assistant) {
        // MP 已配置 ASSIGN_UUID，无需手动生成ID，移除重复UUID逻辑
        assistantMapper.insert(assistant);
        return assistant;
    }

    /**
     * 根据用户ID查询所属助手列表（按创建时间倒序）
     * @param userId 用户ID
     * @return 助手集合
     */
    public List<Assistant> listByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        LambdaQueryWrapper<Assistant> queryWrapper = new LambdaQueryWrapper<Assistant>()
                .eq(Assistant::getUserId, userId)
                .orderByDesc(Assistant::getCreatedAt);
        return assistantMapper.selectList(queryWrapper);
    }

    /**
     * 根据主键ID查询单条助手
     * @param id 助手ID
     * @return 助手实体，不存在返回 null
     */
    public Assistant getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return assistantMapper.selectById(id);
    }

    /**
     * 批量查询助手（按主键），用于列表批量回填名称，避免 N+1 查询
     * @param ids 助手ID集合
     * @return 助手列表
     */
    public List<Assistant> listByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return assistantMapper.selectBatchIds(ids);
    }

    /**
     * 根据ID删除助手（逻辑删除）
     * @param id 助手ID
     * @return true-删除成功 false-删除失败
     */
    public boolean delete(String id) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        return assistantMapper.deleteById(id) > 0;
    }

    /**
     * 更新助手信息（根据主键更新）
     * @param assistant 待更新实体（必须包含主键ID）
     * @return true-更新成功 false-更新失败
     */
    public boolean update(Assistant assistant) {
        if (assistant == null || !StringUtils.hasText(assistant.getId())) {
            return false;
        }
        return assistantMapper.updateById(assistant) > 0;
    }
}