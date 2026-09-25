package com.leyon.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 知识库检索服务
 * 对接 RAGFlow 检索接口，根据问题与数据集ID获取相关上下文内容
 *
 * @author leyon
 */
@Service
public class KnowledgeService implements KnowledgeProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** RAGFlow 接口密钥 */
    @Value("${app.ragflow.api-key}")
    private String apiKey;

    /** RAGFlow 接口地址 */
    @Value("${app.ragflow.endpoint}")
    private String endpoint;

    /** 检索接口路径 */
    private static final String RETRIEVAL_PATH = "/api/v1/retrieval";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 创建带超时配置的 RestTemplate，避免外部请求阻塞
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
    }

    /** 相似度阈值 */
    private static final double SIMILARITY_THRESHOLD = 0.2;
    /** 向量相似度权重 */
    private static final double VECTOR_SIMILARITY_WEIGHT = 0.3;
    /** 检索返回条数 */
    private static final int PAGE_SIZE = 3;

    /**
     * 知识库检索
     *
     * @param question    用户提问
     * @param datasetIds  数据集ID列表
     * @return 拼接后的检索上下文，异常/无数据返回空字符串
     */
    public String queryKnowledgeBase(String question, List<String> datasetIds) {
        return queryKnowledgeBaseWithDetail(question, datasetIds).context();
    }

    /**
     * 知识库检索（结构化结果）
     * 返回上下文文本 + 命中文档名称列表，供 query_end 携带 knowledgebase 引用
     *
     * @param question   用户提问
     * @param datasetIds 数据集ID列表
     * @return 结构化命中结果；外部故障/畸形响应返回 {@link KnowledgeHit#failure()}（v2.39 起与"无命中"分家），
     *         参数不合法或确实没有相关 chunk 时返回 {@link KnowledgeHit#empty()}
     */
    @Override
    public KnowledgeHit queryKnowledgeBaseWithDetail(String question, List<String> datasetIds) {
        // 基础参数校验
        if (!StringUtils.hasText(question) || datasetIds == null || datasetIds.isEmpty()) {
            return KnowledgeHit.empty();
        }

        try {
            // 构造请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 构造请求体（对齐 RAGFlow retrieval 语义：相似度阈值 0.2、向量权重 0.3、top3）
            Map<String, Object> body = new HashMap<>();
            body.put("question", question);
            body.put("dataset_ids", datasetIds);
            body.put("similarity_threshold", SIMILARITY_THRESHOLD);
            body.put("vector_similarity_weight", VECTOR_SIMILARITY_WEIGHT);
            body.put("page", 1);
            body.put("page_size", PAGE_SIZE);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            String requestUrl = endpoint + RETRIEVAL_PATH;

            // 发起请求
            ResponseEntity<String> response = restTemplate.postForEntity(requestUrl, requestEntity, String.class);
            String responseBody = response.getBody();
            if (!StringUtils.hasText(responseBody)) {
                // 合法的"无命中"是 {"code":0,"data":{"chunks":[]}}，空响应体属异常返回
                log.warn("知识库检索返回空响应体，本条回复不带参考上下文：endpoint={}", endpoint);
                return KnowledgeHit.failure();
            }

            // 解析返回数据
            JsonNode rootNode = objectMapper.readTree(responseBody);
            // RAGFlow 的业务错误也走 HTTP 200，必须按 code 判定，否则"密钥失效"会被当成"知识库没有相关内容"
            JsonNode codeNode = rootNode.path("code");
            if (codeNode.isNumber() && codeNode.asInt() != 0) {
                log.warn("知识库检索返回业务错误，本条回复不带参考上下文：code={}，message={}",
                        codeNode.asInt(), rootNode.path("message").asText(""));
                return KnowledgeHit.failure();
            }
            JsonNode chunksNode = rootNode.path("data").path("chunks");
            if (!chunksNode.isArray() || chunksNode.isEmpty()) {
                return KnowledgeHit.empty();
            }

            // 拼接检索内容，同时收集命中文档名称
            StringBuilder context = new StringBuilder();
            List<String> docNames = new ArrayList<>();
            for (JsonNode chunk : chunksNode) {
                String content = chunk.path("content").asText("");
                if (StringUtils.hasText(content)) {
                    context.append(content).append("\n");
                }
                String docName = chunk.path("document_keyword").asText("");
                if (!StringUtils.hasText(docName)) {
                    docName = chunk.path("docnm_kwd").asText("");
                }
                if (StringUtils.hasText(docName) && !docNames.contains(docName)) {
                    docNames.add(docName);
                }
            }
            return new KnowledgeHit(context.toString().trim(), docNames.size(), docNames);

        } catch (Exception e) {
            // 修前此处静默返回 empty()，与"知识库确实没有相关内容"同形：回答照常生成，无人知道它没有依据
            log.warn("知识库检索失败，本条回复不带参考上下文：{}（{}）", e.getMessage(), e.getClass().getSimpleName());
            return KnowledgeHit.failure();
        }
    }

    /**
     * 检索效果测试（F5.5 扩展）
     * 返回命中的 chunk 原始信息（内容 + 相似度 + 文档名），供调试面板预览
     *
     * @param question   测试问题
     * @param datasetIds 数据集ID列表
     * @return 命中 chunk 列表，每个元素含 content/similarity/document
     */
    public List<Map<String, Object>> testRetrieval(String question, List<String> datasetIds) {
        if (!StringUtils.hasText(question) || datasetIds == null || datasetIds.isEmpty()) {
            return List.of();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("question", question);
            body.put("dataset_ids", datasetIds);
            body.put("similarity_threshold", SIMILARITY_THRESHOLD);
            body.put("vector_similarity_weight", VECTOR_SIMILARITY_WEIGHT);
            body.put("page", 1);
            body.put("page_size", PAGE_SIZE);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint + RETRIEVAL_PATH, requestEntity, String.class);
            String responseBody = response.getBody();
            if (!StringUtils.hasText(responseBody)) {
                return List.of();
            }

            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode chunksNode = rootNode.path("data").path("chunks");
            if (!chunksNode.isArray()) {
                return List.of();
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode chunk : chunksNode) {
                Map<String, Object> item = new HashMap<>();
                item.put("content", chunk.path("content").asText(""));
                item.put("similarity", chunk.path("similarity").asDouble(0.0));
                String docName = chunk.path("document_keyword").asText("");
                if (!StringUtils.hasText(docName)) {
                    docName = chunk.path("docnm_kwd").asText("");
                }
                item.put("document", docName);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            // 测试检索异常，静默返回空
            return List.of();
        }
    }

    @Override
    public String getProviderName() {
        return "RAGFlow";
    }
}