package com.leyon.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Map;

/**
 * RAGFlow API 代理控制器
 * 将前端 RAGFlow 请求中转到后端，避免在浏览器端暴露 RAGFlow API Key
 * 创建数据集时同步本地知识库元数据（dataset_id 关联）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/ragflow")
public class RagflowProxyController {

    private static final Logger log = LoggerFactory.getLogger(RagflowProxyController.class);

    /** RAGFlow 接口密钥 */
    @Value("${app.ragflow.api-key}")
    private String apiKey;

    /** RAGFlow 接口地址 */
    @Value("${app.ragflow.endpoint}")
    private String endpoint;

    /** RAGFlow API 基础路径前缀 */
    private static final String RAGFLOW_API_PREFIX = "/api/v1";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeService knowledgeService;

    /**
     * 专用 RestTemplate：用于文档解析等耗时操作，配置更长超时时间
     * 连接超时 10s，读取超时 120s
     */
    private final RestTemplate longRunningRestTemplate;

    public RagflowProxyController(RestTemplate restTemplate,
                                  ObjectMapper objectMapper,
                                  KnowledgeBaseService knowledgeBaseService,
                                  KnowledgeService knowledgeService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeService = knowledgeService;
        this.longRunningRestTemplate = createLongRunningRestTemplate();
    }

    /**
     * 检索效果测试（F5.5 扩展）
     * 输入问题与数据集ID，返回命中的 chunk 与相似度
     */
    @PostMapping("/retrieval-test")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> retrievalTest(@RequestBody Map<String, Object> body) {
        String question = String.valueOf(body.getOrDefault("question", ""));
        @SuppressWarnings("unchecked")
        java.util.List<String> datasetIds = (java.util.List<String>) body.getOrDefault("datasetIds", java.util.List.of());
        if (!StringUtils.hasText(question)) {
            return ApiResponse.paramError("测试问题不能为空");
        }
        if (datasetIds == null || datasetIds.isEmpty()) {
            return ApiResponse.paramError("请先选择知识库");
        }
        return ApiResponse.success(knowledgeService.testRetrieval(question, datasetIds));
    }

    /**
     * 创建专用于长耗时操作的 RestTemplate 实例
     * 文档解析（parseChunks）可能需要数分钟完成
     */
    private static RestTemplate createLongRunningRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);   // 连接超时 10s
        factory.setReadTimeout(120_000);      // 读取超时 120s（文档解析耗时较长）
        return new RestTemplate(factory);
    }

    /**
     * 构造带认证信息的请求头（JSON格式）
     * @return HttpHeaders 实例，已设置 Content-Type 和 Authorization
     */
    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    /**
     * 构造带认证信息的请求头（文件上传格式）
     * @return HttpHeaders 实例，已设置 Authorization（不设置 Content-Type，让 Spring 自动处理 multipart boundary）
     */
    private HttpHeaders createMultipartAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }

    // ==================== 数据集接口 ====================

    /**
     * 获取数据集列表
     * 支持分页参数 page / page_size
     */
    @GetMapping("/datasets")
    public ResponseEntity<String> listDatasets(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "100") int pageSize) {
        try {
            String url = endpoint + RAGFLOW_API_PREFIX + "/datasets?page=" + page + "&page_size=" + pageSize;
            HttpEntity<Void> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
            return response;
        } catch (Exception e) {
            log.error("获取数据集列表失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"code\":500,\"message\":\"获取数据集列表失败\"}");
        }
    }

    /**
     * 创建数据集
     * 转发到 RAGFlow 创建后，将 dataset_id 同步到本地知识库表（建立关联）
     */
    @PostMapping("/datasets")
    public ResponseEntity<String> createDataset(@RequestBody String body, HttpServletRequest request) {
        try {
            String url = endpoint + RAGFLOW_API_PREFIX + "/datasets";
            HttpEntity<String> requestEntity = new HttpEntity<>(body, createAuthHeaders());
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);

            // 创建成功后同步本地知识库元数据（dataset_id 关联）
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                syncKnowledgeBase(response.getBody(), request);
            }
            return response;
        } catch (Exception e) {
            log.error("创建数据集失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"code\":500,\"message\":\"创建数据集失败\"}");
        }
    }

    /**
     * 解析 RAGFlow 创建响应，同步本地知识库表（含 dataset_id 关联）
     */
    private void syncKnowledgeBase(String responseBody, HttpServletRequest request) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            String datasetId = data.path("id").asText("");
            if (!StringUtils.hasText(datasetId)) {
                return;
            }
            String userId = (String) request.getAttribute("userId");
            if (!StringUtils.hasText(userId)) {
                return;
            }
            KnowledgeBase kb = new KnowledgeBase();
            kb.setName(data.path("name").asText("未命名知识库"));
            kb.setDescription(data.path("description").asText(""));
            kb.setDatasetId(datasetId);
            kb.setUserId(userId);
            knowledgeBaseService.create(kb);
            log.info("知识库本地元数据已同步，datasetId:{}，userId:{}", datasetId, userId);
        } catch (Exception e) {
            // 本地同步失败不影响主流程
            log.warn("同步知识库本地元数据失败: {}", e.getMessage());
        }
    }

    /**
     * 删除数据集
     * 将请求体原样转发到 RAGFlow
     */
    @DeleteMapping("/datasets")
    public ResponseEntity<String> deleteDataset(@RequestBody String body) {
        try {
            String url = endpoint + RAGFLOW_API_PREFIX + "/datasets";
            HttpEntity<String> requestEntity = new HttpEntity<>(body, createAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, requestEntity, String.class);
            return response;
        } catch (Exception e) {
            log.error("删除数据集失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"code\":500,\"message\":\"删除数据集失败\"}");
        }
    }

    // ==================== 文档接口 ====================

    /**
     * 获取指定数据集下的文档列表
     * 支持分页参数 page / page_size
     */
    @GetMapping("/datasets/{id}/documents")
    public ResponseEntity<String> listDocuments(
            @PathVariable String id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "100") int pageSize,
            HttpServletRequest request) {
        // 对象级授权：校验数据集归属，防止跨用户越权读取
        if (!knowledgeBaseService.isOwnedDataset(id, (String) request.getAttribute("userId"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("{\"code\":403,\"message\":\"无权访问该数据集\"}");
        }
        try {
            String url = endpoint + RAGFLOW_API_PREFIX + "/datasets/" + id + "/documents?page=" + page + "&page_size=" + pageSize;
            HttpEntity<Void> requestEntity = new HttpEntity<>(createAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
            return response;
        } catch (Exception e) {
            log.error("获取文档列表失败, datasetId={}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"code\":500,\"message\":\"获取文档列表失败\"}");
        }
    }

    /**
     * 上传文档到指定数据集
     * 支持 MultipartFile 文件上传
     */
    @PostMapping("/datasets/{id}/documents")
    public ResponseEntity<String> uploadDocument(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        // 对象级授权：校验数据集归属，防止跨用户越权上传
        if (!knowledgeBaseService.isOwnedDataset(id, (String) request.getAttribute("userId"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("{\"code\":403,\"message\":\"无权访问该数据集\"}");
        }
        try {
            String url = endpoint + RAGFLOW_API_PREFIX + "/datasets/" + id + "/documents";

            // 使用 ByteArrayResource 包装文件内容，确保文件名正确传递
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);

            HttpHeaders headers = createMultipartAuthHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            return response;
        } catch (Exception e) {
            log.error("上传文档失败, datasetId={}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"code\":500,\"message\":\"上传文档失败\"}");
        }
    }

    /**
     * 删除指定数据集下的文档
     * 将请求体原样转发到 RAGFlow
     */
    @DeleteMapping("/datasets/{id}/documents")
    public ResponseEntity<String> deleteDocument(
            @PathVariable String id,
            @RequestBody String body,
            HttpServletRequest request) {
        // 对象级授权：校验数据集归属，防止跨用户越权删除
        if (!knowledgeBaseService.isOwnedDataset(id, (String) request.getAttribute("userId"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("{\"code\":403,\"message\":\"无权访问该数据集\"}");
        }
        try {
            String url = endpoint + RAGFLOW_API_PREFIX + "/datasets/" + id + "/documents";
            HttpEntity<String> requestEntity = new HttpEntity<>(body, createAuthHeaders());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, requestEntity, String.class);
            return response;
        } catch (Exception e) {
            log.error("删除文档失败, datasetId={}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"code\":500,\"message\":\"删除文档失败\"}");
        }
    }

    // ==================== 文档切片接口 ====================

    /**
     * 解析文档切片
     * 将请求体原样转发到 RAGFlow
     */
    @PostMapping("/datasets/{id}/chunks")
    public ResponseEntity<String> parseChunks(
            @PathVariable String id,
            @RequestBody String body,
            HttpServletRequest request) {
        // 对象级授权：校验数据集归属，防止跨用户越权解析
        if (!knowledgeBaseService.isOwnedDataset(id, (String) request.getAttribute("userId"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("{\"code\":403,\"message\":\"无权访问该数据集\"}");
        }
        try {
            String url = endpoint + RAGFLOW_API_PREFIX + "/datasets/" + id + "/chunks";
            HttpEntity<String> requestEntity = new HttpEntity<>(body, createAuthHeaders());
            // 文档解析为耗时操作，使用专用长超时 RestTemplate（读取超时 120s）
            ResponseEntity<String> response = longRunningRestTemplate.postForEntity(url, requestEntity, String.class);
            return response;
        } catch (Exception e) {
            log.error("解析文档切片失败, datasetId={}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"code\":500,\"message\":\"解析文档切片失败\"}");
        }
    }

    // ==================== 配置接口 ====================

    /**
     * 返回 RAGFlow endpoint 配置信息（不含 apiKey）
     * 前端通过此接口获取 RAGFlow 服务地址，用于 WebSocket 等直连场景
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, String>> getConfig() {
        Map<String, String> config = Collections.singletonMap("endpoint", endpoint);
        return ApiResponse.success(config);
    }
}
