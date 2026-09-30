package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.service.UsageStatsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用量统计接口（F7.3 扩展）
 * 只做参数与主体装配，口径规则全在 {@link UsageStatsService}
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final UsageStatsService usageStatsService;

    public StatsController(UsageStatsService usageStatsService) {
        this.usageStatsService = usageStatsService;
    }

    /**
     * 用量统计
     *
     * @param range day / week / month（默认 week，其余值按 week）
     * @param orgId 组织ID；不传=只统计本人，传入时由服务做成员校验（非成员 403）
     */
    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(
            @RequestParam(value = "range", defaultValue = "week") String range,
            @RequestParam(value = "orgId", required = false) String orgId,
            HttpServletRequest request) {
        // 统计主体只取认证属性：不接受请求参数指定"替谁统计"
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(usageStatsService.usage(userId, range, orgId));
    }
}
