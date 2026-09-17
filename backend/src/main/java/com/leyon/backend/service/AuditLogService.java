package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计日志服务
 *
 * @author leyon
 */
@Service
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录一条审计日志
     */
    public void record(AuditLog log) {
        if (log == null) {
            return;
        }
        try {
            auditLogMapper.insert(log);
        } catch (Exception e) {
            // 审计失败不影响主流程
        }
    }

    /**
     * 分页查询审计日志（按创建时间倒序），用于管理端审计追踪
     *
     * @param offset 偏移量
     * @param limit  每页条数
     * @return 审计日志列表
     */
    public List<AuditLog> pageByCreatedDesc(long offset, int limit) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getCreatedAt)
                .last("LIMIT " + limit + " OFFSET " + offset);
        return auditLogMapper.selectList(wrapper);
    }

    /**
     * 审计日志总数
     *
     * @return 总数
     */
    public long countAll() {
        return auditLogMapper.selectCount(new LambdaQueryWrapper<>());
    }
}
