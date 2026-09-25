package com.leyon.backend.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.AuditLog;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Quota;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.AssistantMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.QuotaMapper;
import com.leyon.backend.mapper.RecordMapper;
import com.leyon.backend.mapper.SessionMapper;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.service.AuditLogService;
import com.leyon.backend.service.ArchiveResult;
import com.leyon.backend.service.DataArchiveService;
import com.leyon.backend.service.InviteCodeService;
import com.leyon.backend.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final QuotaMapper quotaMapper;
    private final QuotaService quotaService;
    private final InviteCodeService inviteCodeService;
    private final AuditLogService auditLogService;
    private final DataArchiveService dataArchiveService;

    public AdminController(UserMapper userMapper,
                           AssistantMapper assistantMapper,
                           CallRecordMapper callRecordMapper,
                           RecordMapper recordMapper,
                           SessionMapper sessionMapper,
                           QuotaMapper quotaMapper,
                           QuotaService quotaService,
                           InviteCodeService inviteCodeService,
                           AuditLogService auditLogService,
                           DataArchiveService dataArchiveService) {
        this.userMapper = userMapper;
        this.assistantMapper = assistantMapper;
        this.callRecordMapper = callRecordMapper;
        this.recordMapper = recordMapper;
        this.sessionMapper = sessionMapper;
        this.quotaMapper = quotaMapper;
        this.quotaService = quotaService;
        this.inviteCodeService = inviteCodeService;
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

    /**
     * 配额列表（P2-10）：全部已配置配额（org/user 作用域）
     */
    @GetMapping("/quotas")
    public ApiResponse<List<Quota>> quotas() {
        return ApiResponse.success(quotaMapper.selectList(new LambdaQueryWrapper<Quota>()
                .orderByAsc(Quota::getScopeType)
                .orderByAsc(Quota::getScopeId)));
    }

    /**
     * 环境变量兜底配额（v2.29）：quotas 表无记录的 org/user 生效的就是这份，供管理端展示
     */
    @GetMapping("/quotas/defaults")
    public ApiResponse<Quota> quotaDefaults() {
        return ApiResponse.success(quotaService.getDefaultQuota());
    }

    /**
     * 配置/更新配额（P2-10）：按 scope_type + scope_id UPSERT；某项为空则不修改该维度
     */
    @Audit(action = "QUOTA_UPDATE", targetType = "quota")
    @PutMapping("/quotas")
    public ApiResponse<Void> upsertQuota(@RequestBody Quota quota) {
        if (quota == null || !StringUtils.hasText(quota.getScopeType())
                || !StringUtils.hasText(quota.getScopeId())) {
            return ApiResponse.paramError("scopeType 与 scopeId 不能为空");
        }
        if (!Quota.SCOPE_ORG.equals(quota.getScopeType()) && !Quota.SCOPE_USER.equals(quota.getScopeType())) {
            return ApiResponse.paramError("scopeType 仅支持 org / user");
        }
        Quota exist = quotaMapper.selectOne(new LambdaQueryWrapper<Quota>()
                .eq(Quota::getScopeType, quota.getScopeType())
                .eq(Quota::getScopeId, quota.getScopeId())
                .last("LIMIT 1"));
        if (exist == null) {
            // 新建行以环境变量兜底四项打底：留 NULL 会在 getEffective 的超限比较中拆箱 NPE（局部更新语义只对已存在行生效）
            exist = quotaService.getDefaultQuota();
            exist.setScopeType(quota.getScopeType());
            exist.setScopeId(quota.getScopeId());
            quotaMapper.insert(exist);
        }
        Quota update = new Quota();
        update.setId(exist.getId());
        if (quota.getAssistantLimit() != null) {
            update.setAssistantLimit(quota.getAssistantLimit());
        }
        if (quota.getDailyCallLimit() != null) {
            update.setDailyCallLimit(quota.getDailyCallLimit());
        }
        if (quota.getDailyCallSecLimit() != null) {
            update.setDailyCallSecLimit(quota.getDailyCallSecLimit());
        }
        if (quota.getDailyMsgLimit() != null) {
            update.setDailyMsgLimit(quota.getDailyMsgLimit());
        }
        quotaMapper.updateById(update);
        return ApiResponse.success();
    }

    /**
     * 批量生成注册邀请码（v2.37 邀请码制注册）：码值仅此一次返回，需管理员自行转发
     */
    @Audit(action = "INVITE_CODE_GENERATE", targetType = "invite_code")
    @PostMapping("/invite-codes")
    public ApiResponse<Map<String, Object>> generateInviteCodes(@RequestBody Map<String, Object> body,
                                                                HttpServletRequest request) {
        Object raw = body == null ? null : body.get("count");
        int count;
        if (raw instanceof Number number) {
            count = number.intValue();
        } else {
            return ApiResponse.paramError("count 必须是数字");
        }
        if (count < 1 || count > InviteCodeService.MAX_GENERATE_PER_REQUEST) {
            return ApiResponse.paramError("单次生成数量需在 1~"
                    + InviteCodeService.MAX_GENERATE_PER_REQUEST + " 之间");
        }
        // userId 由 AuthInterceptor 解析后放入请求属性（/api/admin/** 必经该拦截器）
        List<String> codes = inviteCodeService.generate(count, (String) request.getAttribute("userId"));
        Map<String, Object> result = new HashMap<>();
        result.put("codes", codes);
        return ApiResponse.success(result);
    }

    /**
     * 邀请码列表（v2.37）：未使用的排前面，供管理端核对"还有哪些码能发、哪个码被谁用了"
     */
    @GetMapping("/invite-codes")
    public ApiResponse<Map<String, Object>> inviteCodes(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return ApiResponse.success(inviteCodeService.listPage(page, pageSize));
    }
}