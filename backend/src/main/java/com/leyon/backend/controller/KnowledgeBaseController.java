package com.leyon.backend.controller;

import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.model.ApiResponse;
import com.leyon.backend.service.KnowledgeBaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledges")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final RestTemplate restTemplate;

    @Value("${app.ragflow.api-key}")
    private String ragflowApiKey;

    @Value("${app.ragflow.endpoint}")
    private String ragflowEndpoint;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService, RestTemplate restTemplate) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.restTemplate = restTemplate;
    }

    /** 获取当前用户的全部知识库 */
    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ApiResponse.success(knowledgeBaseService.listByUserId(userId));
    }

    /** 创建知识库 */
    @PostMapping
    public ApiResponse<KnowledgeBase> create(@RequestBody KnowledgeBase kb, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        kb.setUserId(userId);
        return ApiResponse.success(knowledgeBaseService.create(kb));
    }

    /** 获取知识库详情 */
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBase> getById(@PathVariable String id) {
        KnowledgeBase kb = knowledgeBaseService.getById(id);
        if (kb == null) {
            return ApiResponse.paramError("知识库不存在");
        }
        return ApiResponse.success(kb);
    }

    /** 更新知识库 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id, @RequestBody KnowledgeBase kb) {
        kb.setId(id);
        boolean updated = knowledgeBaseService.update(kb);
        if (!updated) {
            return ApiResponse.paramError("更新失败");
        }
        return ApiResponse.success();
    }

    /** 删除知识库 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        boolean deleted = knowledgeBaseService.delete(id);
        if (!deleted) {
            return ApiResponse.paramError("删除失败");
        }
        return ApiResponse.success();
    }

    /**
     * 获取 RAGFlow 服务配置（前端需要 endpoint 来直连 RAGFlow SDK）
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, String>> getConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("endpoint", ragflowEndpoint);
        return ApiResponse.success(config);
    }

    /**
     * RAGFlow 代理端点：前端通过后端转发请求到 RAGFlow，避免前端持有 API Key
     */
    @PostMapping("/ragflow-proxy")
    public ResponseEntity<String> ragflowProxy(
            @RequestBody String body,
            @RequestHeader(value = "X-Ragflow-Path", required = false) String ragflowPath,
            HttpServletRequest request) {
        try {
            if (ragflowEndpoint == null || ragflowEndpoint.isBlank()) {
                return ResponseEntity.badRequest().body("{\"error\":\"RAGFlow 未配置\"}");
            }

            // 构建目标 URL
            String targetUrl = ragflowEndpoint + (ragflowPath != null ? ragflowPath : "/api/v1/datasets");

            // 转发请求（携带 API Key 认证）
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", ragflowApiKey);

            ResponseEntity<String> response = restTemplate.exchange(
                    targetUrl,
                    HttpMethod.valueOf(request.getMethod()),
                    new HttpEntity<>(body, headers),
                    String.class
            );

            return response;
        } catch (Exception e) {
            log.error("RAGFlow 代理请求失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("{\"error\":\"RAGFlow 请求失败\"}");
        }
    }
}
