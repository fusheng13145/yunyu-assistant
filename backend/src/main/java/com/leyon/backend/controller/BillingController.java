package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用量配额与账单统计接口（P2-10 用量配额与账单统计）
 * 返回当前用户/组织生效配额、当日用量与剩余量
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final QuotaService quotaService;

    public BillingController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * 用量账单视图：{ quota, current, remaining, period, scopeType, scopeId }
     */
    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(quotaService.aggregateUsage(userId));
    }
}