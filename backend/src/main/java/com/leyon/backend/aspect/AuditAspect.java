package com.leyon.backend.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.annotation.Audit;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 操作审计切面
 * 拦截标注了 @Audit 的方法，记录操作人、动作、目标、IP、结果
 *
 * @author leyon
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * 环绕通知：执行目标方法，记录审计日志（成功/失败）
     */
    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        HttpServletRequest request = findRequest(joinPoint.getArgs());
        String userId = request != null ? (String) request.getAttribute("userId") : null;
        String ip = request != null ? getClientIp(request) : null;
        String targetId = findTargetId(joinPoint.getArgs());

        boolean success = true;
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            success = false;
            record(userId, audit, targetId, ip, success, t.getMessage());
            throw t;
        }
        record(userId, audit, targetId, ip, success, null);
        return result;
    }

    /**
     * 记录审计日志
     */
    private void record(String userId, Audit audit, String targetId, String ip, boolean success, String failReason) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId);
            auditLog.setAction(audit.action());
            auditLog.setTargetType(StringUtils.hasText(audit.targetType()) ? audit.targetType() : null);
            auditLog.setTargetId(targetId);
            auditLog.setIp(ip);
            auditLog.setResult(success ? 1 : 0);
            if (!success && failReason != null) {
                auditLog.setDetail(objectMapper.writeValueAsString(java.util.Map.of("failReason", failReason)));
            }
            auditLogService.record(auditLog);
        } catch (Exception e) {
            log.warn("记录审计日志失败: {}", e.getMessage());
        }
    }

    /**
     * 从方法参数中查找 HttpServletRequest
     */
    private HttpServletRequest findRequest(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof HttpServletRequest request) {
                return request;
            }
        }
        return null;
    }

    /**
     * 从方法参数中查找目标ID（String 类型的 path variable）
     */
    private String findTargetId(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String str && StringUtils.hasText(str) && str.length() <= 64) {
                return str;
            }
        }
        return null;
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
