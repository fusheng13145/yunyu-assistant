package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.Org;
import com.leyon.backend.entity.OrgMember;
import com.leyon.backend.service.OrgService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 组织管理接口（P2-10 多租户与商业化前置）
 * 组织创建/查询/删除、成员管理（加人/改角色/移除/退出）
 * 权限：成员管理操作仅组织 owner 可执行；非成员请求返回 403
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * 创建组织（创建者自动成为 owner）
     */
    @Audit(action = "ORG_CREATE", targetType = "org")
    @PostMapping
    public ApiResponse<Org> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        Org created = orgService.createOrg(userId, body.get("name"), body.get("description"));
        return ApiResponse.success(created);
    }

    /**
     * 查询我的组织列表
     */
    @GetMapping
    public ApiResponse<List<Org>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(orgService.listMyOrgs(userId));
    }

    /**
     * 查询组织成员列表（仅成员可查）
     */
    @GetMapping("/{orgId}/members")
    public ApiResponse<List<OrgMember>> members(@PathVariable String orgId, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(orgService.listMembers(orgId, userId));
    }

    /**
     * 添加成员（仅 owner，按用户名添加，角色 editor/viewer）
     */
    @Audit(action = "ORG_MEMBER_ADD", targetType = "org")
    @PostMapping("/{orgId}/members")
    public ApiResponse<OrgMember> addMember(@PathVariable String orgId,
                                            @RequestBody Map<String, String> body,
                                            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String username = body.get("username");
        if (!StringUtils.hasText(username)) {
            return ApiResponse.paramError("用户名不能为空");
        }
        OrgMember member = orgService.addMember(orgId, userId, username, body.get("role"));
        return ApiResponse.success(member);
    }

    /**
     * 修改成员角色（仅 owner）
     */
    @Audit(action = "ORG_MEMBER_ROLE", targetType = "org")
    @PutMapping("/{orgId}/members/{targetUserId}")
    public ApiResponse<Void> changeRole(@PathVariable String orgId,
                                        @PathVariable String targetUserId,
                                        @RequestBody Map<String, String> body,
                                        HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        orgService.changeRole(orgId, userId, targetUserId, body.get("role"));
        return ApiResponse.success();
    }

    /**
     * 移除成员（owner 移除他人 / 本人退出，owner 不可移除自己）
     */
    @Audit(action = "ORG_MEMBER_REMOVE", targetType = "org")
    @DeleteMapping("/{orgId}/members/{targetUserId}")
    public ApiResponse<Void> removeMember(@PathVariable String orgId,
                                          @PathVariable String targetUserId,
                                          HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        orgService.removeMember(orgId, userId, targetUserId);
        return ApiResponse.success();
    }

    /**
     * 删除组织（仅 owner）
     */
    @Audit(action = "ORG_DELETE", targetType = "org")
    @DeleteMapping("/{orgId}")
    public ApiResponse<Void> delete(@PathVariable String orgId, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        orgService.deleteOrg(orgId, userId);
        return ApiResponse.success();
    }
}