package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Org;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.OrgService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 助手相关接口
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/assistants")
public class AssistantController {

    private final AssistantService assistantService;
    private final OrgService orgService;

    public AssistantController(AssistantService assistantService, OrgService orgService) {
        this.assistantService = assistantService;
        this.orgService = orgService;
    }

    /**
     * 创建助手
     * 归属规则：请求体携带 orgId 且当前用户为该组织 editor(含)以上时归属组织；否则归属个人
     */
    @Audit(action = "ASSISTANT_CREATE", targetType = "assistant")
    @PostMapping
    public ApiResponse<Assistant> create(@RequestBody Assistant assistant, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        assistant.setUserId(userId);
        assistant.setOrgId(resolveCreateOrg(assistant.getOrgId(), userId));
        Assistant created = assistantService.create(assistant);
        return ApiResponse.success(created);
    }

    /**
     * 查询当前用户名下所有助手
     */
    @GetMapping
    public ApiResponse<List<Assistant>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(assistantService.listByUserId(userId));
    }

    /**
     * 分页查询当前用户名下助手（支持关键词模糊搜索名称/描述）
     * 返回结构：{ list, total, page, pageSize }
     */
    @GetMapping("/page")
    public ApiResponse<Map<String, Object>> page(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        long offset = (long) (safePage - 1) * safeSize;

        List<Assistant> list = assistantService.pageByUser(userId, keyword, offset, safeSize);
        long total = assistantService.countByUser(userId, keyword);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return ApiResponse.success(result);
    }

    /**
     * 根据ID查询单个助手（鉴权：个人数据按 userId，组织数据按成员角色 viewer 以上可读）
     */
    @GetMapping("/{id}")
    public ApiResponse<Assistant> getById(@PathVariable String id, HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("助手ID不能为空");
        }
        String userId = (String) request.getAttribute("userId");
        Assistant assistant = assistantService.getById(id);
        if (assistant == null) {
            return ApiResponse.paramError("助手不存在");
        }
        requireRead(assistant.getOrgId(), assistant.getUserId(), userId);
        return ApiResponse.success(assistant);
    }

    /**
     * 删除助手（鉴权：个人数据按 userId，组织数据按成员角色 editor 以上可删）
     */
    @Audit(action = "ASSISTANT_DELETE", targetType = "assistant")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("助手ID不能为空");
        }
        String userId = (String) request.getAttribute("userId");
        Assistant assistant = assistantService.getById(id);
        if (assistant == null) {
            return ApiResponse.paramError("助手不存在或无操作权限");
        }
        requireManage(assistant.getOrgId(), assistant.getUserId(), userId);
        boolean result = assistantService.delete(id);
        if (!result) {
            return ApiResponse.paramError("删除失败");
        }
        return ApiResponse.success();
    }

    /**
     * 更新助手信息（鉴权：个人数据按 userId，组织数据按成员角色 editor 以上可改；不改动归属）
     */
    @Audit(action = "ASSISTANT_UPDATE", targetType = "assistant")
    @PutMapping
    public ApiResponse<Void> update(@RequestBody Assistant assistant, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (assistant == null || !StringUtils.hasText(assistant.getId())) {
            return ApiResponse.paramError("助手ID不能为空");
        }
        // 校验所属用户/组织
        Assistant exist = assistantService.getById(assistant.getId());
        if (exist == null) {
            return ApiResponse.paramError("助手不存在或无操作权限");
        }
        requireManage(exist.getOrgId(), exist.getUserId(), userId);
        assistant.setUserId(exist.getUserId());
        assistant.setOrgId(exist.getOrgId());
        boolean result = assistantService.update(assistant);
        if (!result) {
            return ApiResponse.paramError("更新失败");
        }
        return ApiResponse.success();
    }

    /**
     * 解析创建归属组织：携带 orgId 且当前用户为 editor(含)以上时归属组织；否则归属个人
     */
    private String resolveCreateOrg(String orgId, String userId) {
        if (!StringUtils.hasText(orgId)) {
            return null;
        }
        orgService.requireRole(orgId, userId, Org.ROLE_EDITOR);
        return orgId;
    }

    /**
     * 读取校验：个人资源按 userId；组织资源需为组织成员（viewer 以上）
     */
    private void requireRead(String orgId, String ownerUserId, String userId) {
        if (!StringUtils.hasText(orgId)) {
            if (!userId.equals(ownerUserId)) {
                throw new ForbiddenException("无权访问该助手");
            }
            return;
        }
        orgService.requireMember(orgId, userId);
    }

    /**
     * 管理校验：个人资源按 userId；组织资源需 editor(含)以上
     */
    private void requireManage(String orgId, String ownerUserId, String userId) {
        if (!StringUtils.hasText(orgId)) {
            if (!userId.equals(ownerUserId)) {
                throw new ForbiddenException("无权访问该助手");
            }
            return;
        }
        orgService.requireRole(orgId, userId, Org.ROLE_EDITOR);
    }
}