package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.Org;
import com.leyon.backend.service.AssistantPolicy;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.KnowledgeBaseService;
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
    private final AssistantPolicy assistantPolicy;
    private final KnowledgeBaseService knowledgeBaseService;

    public AssistantController(AssistantService assistantService, OrgService orgService,
                               AssistantPolicy assistantPolicy, KnowledgeBaseService knowledgeBaseService) {
        this.assistantService = assistantService;
        this.orgService = orgService;
        this.assistantPolicy = assistantPolicy;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 创建助手
     * 归属规则：请求体携带 orgId 且当前用户为该组织 editor(含)以上时归属组织；否则归属个人
     */
    @Audit(action = "ASSISTANT_CREATE", targetType = "assistant")
    @PostMapping
    public ApiResponse<Assistant> create(@RequestBody Assistant assistant, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        // ㊺（v2.79 · C-152）：保存侧与读侧同一判据——不可见的数据集 id 在这里点名拒绝，
        // 不再写进库等每轮对话被读侧静默收敛
        String kbError = knowledgeBaseService.rejectInvisibleDatasetIds(assistant.getKnowledgeIds(), userId);
        if (kbError != null) {
            return ApiResponse.paramError(kbError);
        }
        // 越界的模型/温度/最大输出/人设在写库前拒掉并讲清上限：静默改写用户刚填的配置不可诊断
        try {
            assistantPolicy.validateForWrite(assistant);
        } catch (IllegalArgumentException e) {
            return ApiResponse.paramError(e.getMessage());
        }
        assistant.setUserId(userId);
        assistant.setOrgId(resolveCreateOrg(assistant.getOrgId(), userId));
        Assistant created = assistantService.create(assistant);
        // ㊿（v2.79 · C-151）：INSERT 不走乐观锁，DB 默认 0——响应补上版本号，客户端下一轮 PUT 才有仲裁依据
        created.setVersion(0L);
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
    public ApiResponse<Long> update(@RequestBody Assistant assistant, HttpServletRequest request) {
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
        // ㊺（v2.79 · C-152）：与 create 同一判据
        String kbError = knowledgeBaseService.rejectInvisibleDatasetIds(assistant.getKnowledgeIds(), userId);
        if (kbError != null) {
            return ApiResponse.paramError(kbError);
        }
        try {
            assistantPolicy.validateForWrite(assistant);
        } catch (IllegalArgumentException e) {
            return ApiResponse.paramError(e.getMessage());
        }
        assistant.setUserId(exist.getUserId());
        assistant.setOrgId(exist.getOrgId());
        boolean result = assistantService.update(assistant);
        if (!result) {
            // ㊿（v2.79 · C-151）：乐观锁版本不匹配与"助手已被删除"在此同形——都要求用户刷新拿最新行
            return ApiResponse.paramError("助手不存在或已被他人修改，请刷新后重试");
        }
        // ㊿：把自增后的版本号发回去，客户端下一轮保存不用被迫刷新（旧值重放会被拒绝）。
        // 乐观锁拦截器已在 updateById 时把实体的 version 内存值推进到与库一致，直接取它即可，不要再 +1
        return ApiResponse.success(assistant.getVersion());
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