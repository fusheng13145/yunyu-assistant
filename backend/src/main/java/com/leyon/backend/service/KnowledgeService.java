package com.leyon.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索服务
 * 对接 RAGFlow 检索接口，根据问题与数据集ID获取相关上下文内容
 *
 * @author leyon
 */
@Service
public class KnowledgeService {

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

    public KnowledgeService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 知识库检索
     *
     * @param question    用户提问
     * @param datasetIds  数据集ID列表
     * @return 拼接后的检索上下文，异常/无数据返回空字符串
     */
    public String queryKnowledgeBase(String question, List<String> datasetIds) {
        // 基础参数校验
        if (!StringUtils.hasText(question) || datasetIds == null || datasetIds.isEmpty()) {
            return "";
        }

        try {
            // 构造请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 构造请求体
            Map<String, Object> body = new HashMap<>();
            body.put("question", question);
            body.put("dataset_ids", datasetIds);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            String requestUrl = endpoint + RETRIEVAL_PATH;

            // 发起请求
            ResponseEntity<String> response = restTemplate.postForEntity(requestUrl, requestEntity, String.class);
            String responseBody = response.getBody();
            if (!StringUtils.hasText(responseBody)) {
                return "";
            }

            // 解析返回数据
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode chunksNode = rootNode.path("data").path("chunks");
            if (!chunksNode.isArray() || chunksNode.isEmpty()) {
                return "";
            }

            // 拼接检索内容
            StringBuilder context = new StringBuilder();
            for (JsonNode chunk : chunksNode) {
                String content = chunk.path("content").asText("");
                if (StringUtils.hasText(content)) {
                    context.append(content).append("\n");
                }
            }
            return context.toString().trim();

        } catch (RestClientException e) {
            // 网络/接口调用异常，静默降级返回空
            return "";
        } catch (Exception e) {
            // 解析等其他异常，静默降级返回空
            return "";
        }
    }
}