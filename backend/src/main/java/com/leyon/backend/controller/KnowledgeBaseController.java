package com.leyon.backend.controller;

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

        // 拼接完整请求地址
        String path = StringUtils.hasText(ragflowPath) ? ragflowPath : "/api/v1/datasets";
        String targetUrl = ragflowEndpoint + path;

        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", ragflowApiKey);

        try {
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.valueOf(request.getMethod()),
                    entity,
                    String.class
            );
            return response;
        } catch (Exception e) {
            log.error("RAGFlow 代理请求异常，url:{}，msg:{}", targetUrl, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"error\":\"请求 RAGFlow 服务失败\"}");
        }
    }
}