package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.CallRecordService;
import com.leyon.backend.service.RecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通话记录接口
 * 提供通话记录列表与单次通话详情（F7.1 / F7.2）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/call-records")
public class CallRecordController {

    private final CallRecordService callRecordService;
    private final AssistantService assistantService;
    private final RecordService recordService;

    public CallRecordController(CallRecordService callRecordService,
                                AssistantService assistantService,
                                RecordService recordService) {
        this.callRecordService = callRecordService;
        this.assistantService = assistantService;
        this.recordService = recordService;
    }

    /**
     * 通话记录列表（分页 + 按助手过滤）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(value = "assistantId", required = false) String assistantId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        long offset = (long) (safePage - 1) * safeSize;

        List<CallRecord> records = callRecordService.listByUser(userId, assistantId, offset, safeSize);
        long total = callRecordService.countByUser(userId, assistantId);

        // 批量回填助手名称，避免每个记录单独查询造成 N+1
        Map<String, String> assistantNameMap = buildAssistantNameMap(records);

        List<Map<String, Object>> list = new ArrayList<>();
        for (CallRecord record : records) {
            list.add(toListItem(record, assistantNameMap));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return ApiResponse.success(result);
    }

    /**
     * 单次通话详情（含关联消息列表）
     */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id, HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("通话记录ID不能为空");
        }
        String userId = (String) request.getAttribute("userId");
        CallRecord record = callRecordService.getById(id);
        if (record == null) {
            return ApiResponse.paramError("通话记录不存在");
        }
        // 归属校验
        if (!StringUtils.hasText(userId) || !userId.equals(record.getUserId())) {
            return ApiResponse.paramError("无权访问该通话记录");
        }

        List<Record> messages = recordService.listByCallId(id);

        Map<String, Object> result = new HashMap<>(toListItem(record, buildAssistantNameMap(List.of(record))));
        List<Map<String, Object>> messageList = new ArrayList<>();
        for (Record msg : messages) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("message", msg.getMessage());
            m.put("costTime", msg.getCostTime());
            m.put("createdAt", msg.getCreatedAt());
            messageList.add(m);
        }
        result.put("messages", messageList);
        return ApiResponse.success(result);
    }

    /**
     * 组装列表项（补充助手名称，名称来自批量预取的映射）
     */
    private Map<String, Object> toListItem(CallRecord record, Map<String, String> assistantNameMap) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", record.getId());
        item.put("assistantId", record.getAssistantId());
        item.put("status", record.getStatus());
        item.put("durationSec", record.getDurationSec());
        item.put("messageCount", record.getMessageCount());
        item.put("startedAt", record.getStartedAt());
        item.put("endedAt", record.getEndedAt());
        item.put("failReason", record.getFailReason());
        item.put("assistantName", assistantNameMap.getOrDefault(record.getAssistantId(), ""));
        return item;
    }

    /**
     * 批量查询记录涉及的助手名称映射（assistantId -> name），避免 N+1 查询
     */
    private Map<String, String> buildAssistantNameMap(List<CallRecord> records) {
        Map<String, String> nameMap = new HashMap<>();
        if (records == null || records.isEmpty()) {
            return nameMap;
        }
        List<String> assistantIds = new ArrayList<>();
        for (CallRecord record : records) {
            if (StringUtils.hasText(record.getAssistantId())) {
                assistantIds.add(record.getAssistantId());
            }
        }
        if (assistantIds.isEmpty()) {
            return nameMap;
        }
        List<Assistant> assistants = assistantService.listByIds(assistantIds);
        for (Assistant assistant : assistants) {
            if (assistant != null && StringUtils.hasText(assistant.getId())) {
                nameMap.put(assistant.getId(), assistant.getName());
            }
        }
        return nameMap;
    }
}
