package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.service.CallRecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量统计接口（F7.3 扩展）
 * 按日/周/月统计通话次数、总时长、消息数
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CallRecordService callRecordService;

    public StatsController(CallRecordService callRecordService) {
        this.callRecordService = callRecordService;
    }

    /**
     * 用量统计
     *
     * @param range 统计范围：day / week / month（默认 week）
     */
    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(
            @RequestParam(value = "range", defaultValue = "week") String range,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");

        int days = switch (range) {
            case "day" -> 1;
            case "month" -> 30;
            default -> 7;
        };

        LocalDateTime since = LocalDate.now().minusDays(days - 1).atStartOfDay();
        List<CallRecord> records = callRecordService.listByUserSince(userId, since);

        // 按天聚合
        Map<String, long[]> dayMap = new HashMap<>(); // date -> [callCount, durationSec]
        long totalCallCount = 0;
        long totalDurationSec = 0;
        long totalMessageCount = 0;

        for (CallRecord record : records) {
            if (record.getStartedAt() == null) {
                continue;
            }
            String date = record.getStartedAt().toLocalDate().format(DATE_FMT);
            long[] agg = dayMap.computeIfAbsent(date, k -> new long[2]);
            agg[0] += 1;
            agg[1] += record.getDurationSec() == null ? 0 : record.getDurationSec();

            totalCallCount += 1;
            totalDurationSec += record.getDurationSec() == null ? 0 : record.getDurationSec();
            totalMessageCount += record.getMessageCount() == null ? 0 : record.getMessageCount();
        }

        // 生成连续日期序列（缺失日期补零）
        List<Map<String, Object>> dayList = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            String date = LocalDate.now().minusDays(days - 1 - i).format(DATE_FMT);
            long[] agg = dayMap.getOrDefault(date, new long[2]);
            Map<String, Object> item = new HashMap<>();
            item.put("date", date);
            item.put("callCount", agg[0]);
            item.put("durationSec", agg[1]);
            dayList.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("range", range);
        result.put("callCount", totalCallCount);
        result.put("totalDurationSec", totalDurationSec);
        result.put("messageCount", totalMessageCount);
        result.put("days", dayList);
        return ApiResponse.success(result);
    }
}
