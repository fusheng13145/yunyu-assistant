package com.leyon.backend.service;

import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

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
}
