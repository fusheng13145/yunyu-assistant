package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.service.KnowledgeBaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口
 * 提供知识库增删改查、RAGFlow配置获取与请求代理能力
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/knowledges")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final RestTemplate restTemplate;

    @Value("${app.ragflow.api-key:}")
    private String ragflowApiKey;

    @Value("${app.ragflow.endpoint:}")
    private String ragflowEndpoint;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService, RestTemplate restTemplate) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.restTemplate = restTemplate;
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
     */
    @Audit(action = "KB_CREATE", targetType = "knowledge_base")
    @PostMapping
    public ApiResponse<KnowledgeBase> create(@RequestBody KnowledgeBase kb, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        kb.setUserId(userId);
        return ApiResponse.success(knowledgeBaseService.create(kb));
    }

    /**
     * 根据ID查询知识库详情（增加归属权限校验）
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
        // 校验数据归属
        if (!StringUtils.hasText(loginUserId) || !loginUserId.equals(kb.getUserId())) {
            return ApiResponse.paramError("无权访问该知识库");
        }
        return ApiResponse.success(kb);
    }

    /**
     * 更新知识库（增加归属权限校验）
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
        if (!loginUserId.equals(existKb.getUserId())) {
            return ApiResponse.paramError("无权修改该知识库");
        }
        kb.setId(id);
        kb.setUserId(loginUserId);
        boolean updated = knowledgeBaseService.update(kb);
        if (!updated) {
            return ApiResponse.paramError("更新失败");
        }
        return ApiResponse.success();
    }

    /**
     * 删除知识库（增加归属权限校验）
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
        if (!loginUserId.equals(existKb.getUserId())) {
            return ApiResponse.paramError("无权删除该知识库");
        }
        boolean deleted = knowledgeBaseService.delete(id);
        if (!deleted) {
            return ApiResponse.paramError("删除失败");
        }
        return ApiResponse.success();
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

    /**
     * RAGFlow 请求代理
     * 中转前端请求，避免前端暴露密钥
     */
    @PostMapping("/ragflow-proxy")
    public ResponseEntity<String> ragflowProxy(
            @RequestBody String body,
            @RequestHeader(value = "X-Ragflow-Path", required = false) String ragflowPath,
            HttpServletRequest request) {
        // 配置校验
        if (!StringUtils.hasText(ragflowEndpoint)) {
            return ResponseEntity.badRequest().body("{\"error\":\"RAGFlow 服务地址未配置\"}");
        }
        if (!StringUtils.hasText(ragflowApiKey)) {
            return ResponseEntity.badRequest().body("{\"error\":\"RAGFlow 密钥未配置\"}");
        }

        // 拼接完整请求地址（path 需经白名单校验，防止 SSRF/代理滥用）
        String path = buildWhitelistedPath(ragflowPath);
        if (path == null) {
            return ResponseEntity.badRequest().body("{\"error\":\"非法的转发路径\"}");
        }
        String targetUrl = ragflowEndpoint + path;

        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ragflowApiKey);

        // 仅允许安全方法，避免任意方法转发
        String httpMethod = request.getMethod();
        if (!isAllowedMethod(httpMethod)) {
            return ResponseEntity.badRequest().body("{\"error\":\"不允许的转发方法\"}");
        }

        try {
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.valueOf(httpMethod),
                    entity,
                    String.class
            );
            return response;
        } catch (Exception e) {
            log.error("RAGFlow 代理请求异常，url:{}，msg:{}", targetUrl, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"error\":\"请求 RAGFlow 服务失败\"}");
        }
    }

    /**
     * 转发路径白名单校验：
     * 仅允许相对路径且必须以 /api/v1/ 开头，禁用协议、双斜杠、路径穿越。
     *
     * @param rawPath 客户端提供的路径（可为空）
     * @return 校验通过的路径，非法返回 null
     */
    private String buildWhitelistedPath(String rawPath) {
        String path = StringUtils.hasText(rawPath) ? rawPath.trim() : "/api/v1/datasets";
        // 必须以 / 开头，且非协议形式、无双斜杠、无路径穿越
        if (!path.startsWith("/api/v1/")) {
            return null;
        }
        if (path.toLowerCase().startsWith("http://") || path.toLowerCase().startsWith("https://")) {
            return null;
        }
        if (path.contains("://") || path.contains("//") || path.contains("..")) {
            return null;
        }
        return path;
    }

    /**
     * 是否允许的转发方法
     */
    private boolean isAllowedMethod(String method) {
        if (method == null) {
            return false;
        }
        return switch (method.toUpperCase()) {
            case "GET", "POST", "PUT", "DELETE" -> true;
            default -> false;
        };
    }
}