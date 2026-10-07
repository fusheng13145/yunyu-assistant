package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.UserMemory;
import com.leyon.backend.service.UserMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户长期记忆接口（v2.85 · ⑫ 跨会话长期记忆）
 * 走 AuthInterceptor（user 鉴权），数据严格按 userId 隔离；删除带归属校验
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/memories")
public class UserMemoryController {

    private final UserMemoryService userMemoryService;

    public UserMemoryController(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    /** 当前用户的全量记忆（时间倒序） */
    @GetMapping
    public ApiResponse<List<UserMemory>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(userMemoryService.listByUser(userId));
    }

    /** 删除一条记忆（归属校验：只能删自己的） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (!userMemoryService.delete(id, userId)) {
            return ApiResponse.paramError("记忆不存在或无权限");
        }
        return ApiResponse.success();
    }
}
