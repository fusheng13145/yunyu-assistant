package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.entity.Org;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.OrgService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口
 * 提供知识库增删改查与 RAGFlow 配置获取
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/knowledges")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final OrgService orgService;

    @Value("${app.ragflow.endpoint:}")
    private String ragflowEndpoint;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService, OrgService orgService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.orgService = orgService;
    }

    /**
     * 查询当前用户名下所有知识库
     */
    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(knowledgeBaseService.listByUserId(userId));
    }

    /**
     * 创建知识库
     * 归属规则：请求体携带 orgId 且当前用户为该组织 editor(含)以上时归属组织；否则归属个人
     */
    @Audit(action = "KB_CREATE", targetType = "knowledge_base")
    @PostMapping
    public ApiResponse<KnowledgeBase> create(@RequestBody KnowledgeBase kb, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        kb.setUserId(userId);
        kb.setOrgId(resolveCreateOrg(kb.getOrgId(), userId));
        return ApiResponse.success(knowledgeBaseService.create(kb));
    }

    /**
     * 根据ID查询知识库详情（鉴权：个人数据按 userId，组织数据按成员角色 viewer 以上可读）
     */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBase> getById(@PathVariable String id, HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("知识库ID不能为空");
        }
        String loginUserId = (String) request.getAttribute("userId");
        KnowledgeBase kb = knowledgeBaseService.getById(id);
        if (kb == null) {
            return ApiResponse.paramError("知识库不存在");
        }
        requireRead(kb.getOrgId(), kb.getUserId(), loginUserId);
        return ApiResponse.success(kb);
    }

    /**
     * 更新知识库（鉴权：个人数据按 userId，组织数据按成员角色 editor 以上可改；不改动归属）
     */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id,
                                    @RequestBody KnowledgeBase kb,
                                    HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("知识库ID不能为空");
        }
        String loginUserId = (String) request.getAttribute("userId");
        KnowledgeBase existKb = knowledgeBaseService.getById(id);
        if (existKb == null) {
            return ApiResponse.paramError("知识库不存在");
        }
        requireManage(existKb.getOrgId(), existKb.getUserId(), loginUserId);
        kb.setId(id);
        kb.setUserId(existKb.getUserId());
        kb.setOrgId(existKb.getOrgId());
        boolean updated = knowledgeBaseService.update(kb);
        if (!updated) {
            return ApiResponse.paramError("更新失败");
        }
        return ApiResponse.success();
    }

    /**
     * 删除知识库（鉴权：个人数据按 userId，组织数据按成员角色 editor 以上可删）
     */
    @Audit(action = "KB_DELETE", targetType = "knowledge_base")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        if (!StringUtils.hasText(id)) {
            return ApiResponse.paramError("知识库ID不能为空");
        }
        String loginUserId = (String) request.getAttribute("userId");
        KnowledgeBase existKb = knowledgeBaseService.getById(id);
        if (existKb == null) {
            return ApiResponse.paramError("知识库不存在");
        }
        requireManage(existKb.getOrgId(), existKb.getUserId(), loginUserId);
        boolean deleted = knowledgeBaseService.delete(id);
        if (!deleted) {
            return ApiResponse.paramError("删除失败");
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
            if (!StringUtils.hasText(userId) || !userId.equals(ownerUserId)) {
                throw new ForbiddenException("无权访问该知识库");
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
            if (!StringUtils.hasText(userId) || !userId.equals(ownerUserId)) {
                throw new ForbiddenException("无权访问该知识库");
            }
            return;
        }
        orgService.requireRole(orgId, userId, Org.ROLE_EDITOR);
    }

    /**
     * 获取 RAGFlow 服务地址配置
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, String>> getConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("endpoint", ragflowEndpoint);
        return ApiResponse.success(config);
    }
}