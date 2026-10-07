package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.UserMemory;
import com.leyon.backend.mapper.UserMemoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户长期记忆服务（v2.85 · C-161，收口候选 ⑫）
 *
 * 写入路径只有 save_memory 工具（LLM 显式保存）——没有"自动提取"：
 * 自动沉淀需要另一个模型回合来判定"这条值得记"，先规则化后模型化的第一步就是只给工具不给自动提取。
 * 会话注入：{@link #formatForPrompt(String)} 把记忆拼进系统提示（只读）。
 * 用户可见可删：listByUser / delete（归属校验防越权删他人记忆）。
 *
 * @author leyon
 */
@Service
public class UserMemoryService {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryService.class);

    /** 单条记忆最大长度（字符） */
    private static final int MAX_CONTENT_LENGTH = 500;

    /** 注入系统提示的单人记忆条数上限：记忆是提示词的一部分，无限注入等于把提示词当存储 */
    private static final int MAX_INJECT_COUNT = 20;

    private final UserMemoryMapper userMemoryMapper;

    public UserMemoryService(UserMemoryMapper userMemoryMapper) {
        this.userMemoryMapper = userMemoryMapper;
    }

    /**
     * 保存一条记忆（save_memory 工具的落点）
     *
     * @return 保存后的实体
     */
    public UserMemory save(String userId, String content) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        String clamped = content.trim();
        if (clamped.length() > MAX_CONTENT_LENGTH) {
            clamped = clamped.substring(0, MAX_CONTENT_LENGTH);
        }
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setContent(clamped);
        userMemoryMapper.insert(memory);
        log.info("用户长期记忆已保存，用户ID:{}", userId);
        return memory;
    }

    /** 用户的全量记忆（时间倒序） */
    public List<UserMemory> listByUser(String userId) {
        return userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByDesc(UserMemory::getCreatedAt));
    }

    /**
     * 删除记忆（归属校验：只能删自己的）
     *
     * @return true 表示删除成功；false 表示不存在或无权限
     */
    public boolean delete(String id, String userId) {
        UserMemory memory = userMemoryMapper.selectById(id);
        if (memory == null || !StringUtils.hasText(userId) || !userId.equals(memory.getUserId())) {
            return false;
        }
        return userMemoryMapper.deleteById(id) > 0;
    }

    /**
     * 会话注入形态：拼进系统提示的记忆段（只读）；无记忆返回空串
     */
    public String formatForPrompt(String userId) {
        if (!StringUtils.hasText(userId)) {
            return "";
        }
        List<UserMemory> memories = userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .orderByDesc(UserMemory::getCreatedAt)
                .last("LIMIT " + MAX_INJECT_COUNT));
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n[用户长期记忆]（以下是此前对话中明确保存的关于该用户的长期信息，回答时可参考）");
        for (UserMemory memory : memories) {
            sb.append("\n- ").append(memory.getContent());
        }
        return sb.toString();
    }
}
