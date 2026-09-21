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
    private final QuotaService quotaService;

    public AssistantService(AssistantMapper assistantMapper, QuotaService quotaService) {
        this.assistantMapper = assistantMapper;
        this.quotaService = quotaService;
    }

    /**
     * 创建助手信息（创建前校验助手数量配额，超限抛 403）
     * @param assistant 助手实体（userId/orgId 由 Controller 归属解析后写入）
     * @return 保存后的助手对象
     */
    public Assistant create(Assistant assistant) {
        // 创建前校验助手数量配额（QuotaService 内部按 org 优先 / user 兜底定位作用域），超限抛 403
        quotaService.checkCreateAssistant(assistant.getUserId());
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
     * 分页查询所属助手（按创建时间倒序），支持关键词模糊匹配名称/描述
     *
     * @param userId   用户ID
     * @param keyword  关键词（可为空，空则查询全部）
     * @param offset   偏移量
     * @param limit    每页条数
     * @return 当前页助手列表
     */
    public List<Assistant> pageByUser(String userId, String keyword, long offset, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        LambdaQueryWrapper<Assistant> queryWrapper = new LambdaQueryWrapper<Assistant>()
                .eq(Assistant::getUserId, userId)
                .orderByDesc(Assistant::getCreatedAt);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            queryWrapper.and(w -> w.like(Assistant::getName, kw)
                    .or().like(Assistant::getDescription, kw));
        }
        queryWrapper.last("LIMIT " + limit + " OFFSET " + offset);
        return assistantMapper.selectList(queryWrapper);
    }

    /**
     * 统计所属助手数量（支持关键词过滤），用于分页总数
     *
     * @param userId  用户ID
     * @param keyword 关键词（可为空）
     * @return 助手总数
     */
    public long countByUser(String userId, String keyword) {
        if (!StringUtils.hasText(userId)) {
            return 0L;
        }
        LambdaQueryWrapper<Assistant> queryWrapper = new LambdaQueryWrapper<Assistant>()
                .eq(Assistant::getUserId, userId);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            queryWrapper.and(w -> w.like(Assistant::getName, kw)
                    .or().like(Assistant::getDescription, kw));
        }
        return assistantMapper.selectCount(queryWrapper);
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