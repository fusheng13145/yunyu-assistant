package com.leyon.backend.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.AssistantMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.RecordMapper;
import com.leyon.backend.mapper.SessionMapper;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.service.AuditLogService;
import com.leyon.backend.service.ArchiveResult;
import com.leyon.backend.service.DataArchiveService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端接口（仅管理员）
 * 提供平台用量总览、审计日志追踪、用户列表能力（/api/admin/** 由 AdminAuthInterceptor 鉴权）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserMapper userMapper;
    private final AssistantMapper assistantMapper;
    private final CallRecordMapper callRecordMapper;
    private final RecordMapper recordMapper;
    private final SessionMapper sessionMapper;
    private final AuditLogService auditLogService;
    private final DataArchiveService dataArchiveService;

    public AdminController(UserMapper userMapper,
                           AssistantMapper assistantMapper,
                           CallRecordMapper callRecordMapper,
                           RecordMapper recordMapper,
                           SessionMapper sessionMapper,
                           AuditLogService auditLogService,
                           DataArchiveService dataArchiveService) {
        this.userMapper = userMapper;
        this.assistantMapper = assistantMapper;
        this.callRecordMapper = callRecordMapper;
        this.recordMapper = recordMapper;
        this.sessionMapper = sessionMapper;
        this.auditLogService = auditLogService;
        this.dataArchiveService = dataArchiveService;
    }

    /**
     * 平台用量总览
     */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> result = new HashMap<>();
        result.put("userCount", userMapper.selectCount(new LambdaQueryWrapper<>()));
        result.put("assistantCount", assistantMapper.selectCount(new LambdaQueryWrapper<>()));
        result.put("callRecordCount", callRecordMapper.selectCount(new LambdaQueryWrapper<>()));
        result.put("messageCount", recordMapper.selectCount(new LambdaQueryWrapper<>()));
        result.put("sessionCount", sessionMapper.selectCount(new LambdaQueryWrapper<>()));
        result.put("auditLogCount", auditLogService.countAll());
        return ApiResponse.success(result);
    }

    /**
     * 审计日志列表（分页，按时间倒序）
     */
    @GetMapping("/audit-logs")
    public ApiResponse<Map<String, Object>> auditLogs(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        long offset = (long) (safePage - 1) * safeSize;

        List<AuditLog> list = auditLogService.pageByCreatedDesc(offset, safeSize);
        long total = auditLogService.countAll();

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return ApiResponse.success(result);
    }

    /**
     * 用户列表（分页 + 关键词搜索用户名/昵称）
     */
    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        long offset = (long) (safePage - 1) * safeSize;

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getNickname, kw));
        }
        // 列表用克隆副本追加分页，避免 LIMIT 污染 count 查询
        List<User> list = userMapper.selectList(wrapper.clone().last("LIMIT " + safeSize + " OFFSET " + offset));
        long total = userMapper.selectCount(wrapper);

        // 密码脱敏
        for (User user : list) {
            user.setPassword(null);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return ApiResponse.success(result);
    }

    /**
     * 数据归档概览（P2-9）：三表总量 / 超期量 / 保留天数 + 定时开关状态，只读
     */
    @GetMapping("/archive/overview")
    public ApiResponse<Map<String, Object>> archiveOverview() {
        return ApiResponse.success(dataArchiveService.overview());
    }

    /**
     * 手动执行数据归档（P2-9）：超期数据复制到 *_archive 归档表后物理删除源表，并清理录音文件
     */
    @PostMapping("/archive/run")
    public ApiResponse<Map<String, Object>> archiveRun() {
        ArchiveResult result = dataArchiveService.runArchive();
        dataArchiveService.deleteRecordings(result);
        Map<String, Object> data = new HashMap<>();
        data.put("recordsArchived", result.getRecordsArchived());
        data.put("callRecordsArchived", result.getCallRecordsArchived());
        data.put("auditLogsArchived", result.getAuditLogsArchived());
        data.put("recordingsDeleted", result.getRecordingsDeleted());
        data.put("recordingsFailed", result.getRecordingsFailed());
        data.put("startedAt", result.getStartedAt());
        data.put("finishedAt", result.getFinishedAt());
        return ApiResponse.success(data);
    }
}