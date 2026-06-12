package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 网络搜索工具
 * 供 Spring AI 调用，发起联网搜索并解析返回结果
 *
 * @author leyon
 */
@Component
public class SearchTool {

    /** 搜索接口密钥 */
    @Value("${app.search.api-key}")
    private String apiKey;

    /** 搜索接口地址 */
    @Value("${app.search.endpoint}")
    private String endpoint;

    /** 默认返回结果条数 */
    private static final int DEFAULT_MAX_RESULT = 5;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public SearchTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册 AI 工具回调：网络搜索
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback webSearchFunction() {
        return FunctionToolCallback
                .builder("web_search", (Function<SearchRequest, String>) this::search)
                .description("联网搜索，获取实时互联网信息，入参为搜索关键词与最大返回条数")
                .inputType(SearchRequest.class)
                .build();
    }

    /**
     * 执行网络搜索请求
     *
     * @param request 搜索请求参数
     * @return 拼接后的搜索结果 / 错误提示
     */
    public String search(SearchRequest request) {
        // 基础参数校验
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return "搜索失败：搜索关键词不能为空";
        }

        try {
            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 结果条数容错处理
            int resultCount = request.getMaxResults() != null ? request.getMaxResults() : DEFAULT_MAX_RESULT;

            // 构建请求体
            Map<String, Object> body = new HashMap<>();
            body.put("query", request.getQuery());
            body.put("count", resultCount);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(endpoint, entity, String.class);
            if (response == null) {
                return "搜索失败：接口返回数据为空";
            }

            // 解析响应 JSON
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("data").path("webPages").path("value");
            if (!results.isArray() || results.isEmpty()) {
                return "未查询到相关内容";
            }

            // 拼接结果文本
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : results) {
                String title = item.path("name").asText("");
                String content = item.path("snippet").asText("");
                sb.append(title).append(": ").append(content).append("\n");
            }
            return sb.toString();

        } catch (RestClientException e) {
            return "搜索请求异常：接口调用失败，" + e.getMessage();
        } catch (Exception e) {
            return "搜索解析异常：" + e.getMessage();
        }
    }

    /**
     * 搜索请求入参
     */
    public static class SearchRequest {
        /** 搜索关键词 */
        private String query;
        /** 最大返回结果数 */
        private Integer maxResults;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public Integer getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(Integer maxResults) {
            this.maxResults = maxResults;
        }
    }
}