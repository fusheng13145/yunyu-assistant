package com.leyon.backend.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.service.AuditLogService;
import com.leyon.backend.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 操作审计切面
 * 拦截标注了 @Audit 的方法，记录操作人、动作、目标、IP、结果
 * <p>
 * IP 自 v2.44 起与限流桶键同口径（{@link ClientIpResolver}）：审计的价值在于事后能指认同一个
 * 来源，直取 X-Forwarded-For 第一段等于让被审计者自己填写落库的来源字段。
 *
 * @author leyon
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    public AuditAspect(AuditLogService auditLogService, ObjectMapper objectMapper,
                       ClientIpResolver clientIpResolver) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
    }

    /** 被审计方法可用此 request 属性补充"改了什么"，见 {@link #record} */
    static final String DETAIL_ATTRIBUTE = "auditDetail";

    /** {@code ApiResponse.success} 家族的固定业务码；非此值即一次业务失败 */
    private static final int BUSINESS_SUCCESS_CODE = 200;

    /**
     * 环绕通知：执行目标方法，记录审计日志（成功/失败）
     * <p>
     * 成败有两条判据，缺一不可：抛异常（开放侧与 service 层错误走这条），以及
     * 返回体业务码非 200（管理侧拒绝走 {@code ApiResponse.paramError}，HTTP 仍是 200，见手册 4.4）。
     * 只判前一条会把"被拒绝的变更"记成成功，而这类行恰恰是最需要事后指认的。
     */
    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        HttpServletRequest request = findRequest(joinPoint.getArgs());
        String userId = request != null ? (String) request.getAttribute("userId") : null;
        String ip = request != null ? clientIpResolver.resolve(request) : null;
        String targetId = findTargetId(joinPoint.getArgs());

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            record(userId, audit, targetId, ip, false, t.getMessage(), detailOf(request));
            throw t;
        }
        // 变更详情由被审计方法自己写入（它才知道改前的值），所以必须在 proceed 之后读
        String businessReason = businessFailureReason(result);
        record(userId, audit, targetId, ip, businessReason == null, businessReason, detailOf(request));
        return result;
    }

    /**
     * 返回体是否表示一次业务失败；是则给出落进 {@code detail.failReason} 的原因，否则返回 null。
     * <p>
     * 判据只覆盖 {@code ApiResponse} 这一种返回形状——全部 19 个 {@code @Audit} 端点都是它。
     * 其它形状一律不猜（维持"没抛异常即成功"），否则新增返回类型会让审计朝反方向撒谎。
     */
    private static String businessFailureReason(Object result) {
        if (!(result instanceof ApiResponse<?> response) || response.getCode() == BUSINESS_SUCCESS_CODE) {
            return null;
        }
        return StringUtils.hasText(response.getMessage())
                ? response.getMessage()
                : "业务码 " + response.getCode();
    }

    private Object detailOf(HttpServletRequest request) {
        return request == null ? null : request.getAttribute(DETAIL_ATTRIBUTE);
    }

    /**
     * 记录审计日志
     * <p>
     * {@code detail} 自 v2.46 起不只是"失败原因"：只有 action + targetId 的审计能指认"谁改过哪个应用"，
     * 指认不了"改成了什么"，而能力放开（外呼要花钱、语音能建会话）事后必须可追责。
     * 机制保持最小——被审计方法往 request 放一个 Map，切面原样序列化，不引入表达式或注解参数。
     */
    private void record(String userId, Audit audit, String targetId, String ip, boolean success,
                        String failReason, Object changeDetail) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        if (changeDetail instanceof Map<?, ?> payload) {
            payload.forEach((key, value) -> detail.put(String.valueOf(key), value));
        }
        if (!success && failReason != null) {
            detail.put("failReason", failReason);
        }
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setUserId(userId);
            auditLog.setAction(audit.action());
            auditLog.setTargetType(StringUtils.hasText(audit.targetType()) ? audit.targetType() : null);
            auditLog.setTargetId(targetId);
            auditLog.setIp(ip);
            auditLog.setResult(success ? 1 : 0);
            auditLog.setDetail(serialiseDetail(audit, detail));
            auditLogService.record(auditLog);
        } catch (Exception e) {
            log.warn("记录审计日志失败: {}", e.getMessage());
        }
    }

    /**
     * 详情序列化失败只丢详情，不丢整行：审计行的价值在"谁在何时动了哪个对象"，
     * 让一个附加字段的编码问题把这条指认一起带走，等于给失败路径开了免审计的后门。
     */
    private String serialiseDetail(Audit audit, Map<String, Object> detail) {
        if (detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("审计详情序列化失败，仅丢弃详情：action={} {}", audit.action(), e.getMessage());
            return null;
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
}
