package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.CallRecordService;
import com.leyon.backend.service.RecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通话记录接口
 * 提供通话记录列表与单次通话详情（F7.1 / F7.2），以及通话录音上传/回放（P2-8）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/call-records")
public class CallRecordController {

    private final Logger logger = LoggerFactory.getLogger(CallRecordController.class);

    /** 通话录音存储目录（前端 MediaRecorder 录制上传） */
    @Value("${app.recording.dir:./data/recordings}")
    private String recordingDir;

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
     * 上传通话录音（multipart file，前端 MediaRecorder 录制 webm/opus）
     * 文件以 {callId}.webm 存入 app.recording.dir，并回写 call_records.recording_name
     */
    @PostMapping("/{id}/recording")
    public ApiResponse<Void> uploadRecording(@PathVariable String id,
                                             @RequestParam("file") MultipartFile file,
                                             HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("通话记录ID不能为空");
        }
        if (file == null || file.isEmpty()) {
            return ApiResponse.paramError("录音文件不能为空");
        }
        String userId = (String) request.getAttribute("userId");
        CallRecord record = callRecordService.getById(id);
        if (record == null) {
            return ApiResponse.paramError("通话记录不存在");
        }
        // 归属校验：仅本人可上传
        if (!StringUtils.hasText(userId) || !userId.equals(record.getUserId())) {
            return ApiResponse.paramError("无权访问该通话记录");
        }
        try {
            Path dir = Path.of(recordingDir);
            Files.createDirectories(dir);
            String fileName = id + ".webm";
            Path target = dir.resolve(fileName);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            record.setRecordingName(fileName);
            callRecordService.update(record);
            return ApiResponse.success();
        } catch (Exception e) {
            logger.error("保存通话录音失败，通话ID:{}", id, e);
            return ApiResponse.paramError("保存录音失败");
        }
    }

    /**
     * 下载通话录音（audio/webm 流，供前端回放；鉴权经 AuthInterceptor + 归属校验）
     */
    @GetMapping("/{id}/recording")
    public ResponseEntity<Resource> downloadRecording(@PathVariable String id, HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ResponseEntity.badRequest().build();
        }
        String userId = (String) request.getAttribute("userId");
        CallRecord record = callRecordService.getById(id);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        // 归属校验：仅本人可回放
        if (!StringUtils.hasText(userId) || !userId.equals(record.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!StringUtils.hasText(record.getRecordingName())) {
            return ResponseEntity.notFound().build();
        }
        File file = Path.of(recordingDir, record.getRecordingName()).toFile();
        if (!file.exists() || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "audio/webm")
                .header("Content-Disposition", "inline; filename=\"" + record.getRecordingName() + "\"")
                .contentLength(file.length())
                .body(new FileSystemResource(file));
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
        item.put("recording", StringUtils.hasText(record.getRecordingName()));
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
