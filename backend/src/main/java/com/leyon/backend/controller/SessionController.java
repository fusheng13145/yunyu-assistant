package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.service.RecordService;
import com.leyon.backend.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话相关接口
 * 提供会话创建、列表、更新（标题/置顶）、删除、历史消息查询能力
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final RecordService recordService;

    public SessionController(SessionService sessionService, RecordService recordService) {
        this.sessionService = sessionService;
        this.recordService = recordService;
    }

    /**
     * 创建会话（归属当前用户；请求体携带 orgId 且为组织 editor 以上时归属组织）
     */
    @Audit(action = "SESSION_CREATE", targetType = "session")
    @PostMapping
    public ApiResponse<Session> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String assistantId = body.get("assistantId");
        if (!StringUtils.hasText(assistantId)) {
            return ApiResponse.paramError("助手ID不能为空");
        }
        Session created = sessionService.create(userId, assistantId, body.get("title"), body.get("orgId"));
        return ApiResponse.success(created);
    }

    /**
     * 查询当前用户的会话列表（可按助手过滤）
     */
    @GetMapping
    public ApiResponse<List<Session>> list(@RequestParam(required = false) String assistantId,
                                           HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(sessionService.listByUser(userId, assistantId));
    }

    /**
     * 更新会话（标题 / 置顶，仅更新非空字段）
     */
    @Audit(action = "SESSION_UPDATE", targetType = "session")
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("会话ID不能为空");
        }
        String userId = (String) request.getAttribute("userId");
        String title = body.get("title") == null ? null : body.get("title").toString();
        Integer isPinned = null;
        Object pinnedObj = body.get("isPinned");
        if (pinnedObj instanceof Boolean b) {
            isPinned = b ? Session.PINNED : Session.NOT_PINNED;
        }
        boolean result = sessionService.update(id, userId, title, isPinned);
        if (!result) {
            return ApiResponse.paramError("会话不存在或无操作权限");
        }
        return ApiResponse.success();
    }

    /**
     * 查询会话历史消息（分页，倒序最新在前；切换会话时前端回显）
     * 返回结构：{ list, total, page, pageSize }
     * 仅返回本人会话
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<Map<String, Object>> messages(
            @PathVariable String id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
            HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("会话ID不能为空");
        }
        String userId = (String) request.getAttribute("userId");
        Session owned = sessionService.getOwned(id, userId);
        if (owned == null) {
            return ApiResponse.paramError("会话不存在或无操作权限");
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long offset = (long) (safePage - 1) * safeSize;

        List<Record> list = recordService.pageBySessionIdDesc(id, offset, safeSize);
        long total = recordService.countBySessionId(id);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return ApiResponse.success(result);
    }

    /**
     * 删除会话（逻辑删除）
     */
    @Audit(action = "SESSION_DELETE", targetType = "session")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("会话ID不能为空");
        }
        String userId = (String) request.getAttribute("userId");
        boolean result = sessionService.delete(id, userId);
        if (!result) {
            return ApiResponse.paramError("会话不存在或无操作权限");
        }
        return ApiResponse.success();
    }
}