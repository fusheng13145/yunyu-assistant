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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 网络搜索工具
 * 供 Spring AI 调用，发起联网搜索并解析返回结果
 *
 * <p>依赖 SEARCH_API_KEY 与 SEARCH_ENDPOINT：任一未配置时本工具不注册。
 *
 * @author leyon
 */
@Component
@RequiresProperty({"app.search.api-key", "app.search.endpoint"})
public class SearchTool {

    /** 搜索接口密钥 */
    @Value("${app.search.api-key}")
    private String apiKey;

    /** 搜索接口地址 */
    @Value("${app.search.endpoint}")
    private String endpoint;

    /** 默认返回结果条数 */
    private static final int DEFAULT_MAX_RESULT = 5;

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public SearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 创建带超时配置的 RestTemplate，避免搜索请求阻塞
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
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
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return "搜索失败：搜索关键词不能为空";
        }
        SearchOutcome outcome = searchHits(request.getQuery(), request.getMaxResults());
        if (outcome.error() != null) {
            return outcome.error();
        }
        if (outcome.hits().isEmpty()) {
            return "未查询到相关内容";
        }
        StringBuilder sb = new StringBuilder();
        for (SearchHit hit : outcome.hits()) {
            sb.append(hit.title()).append(": ").append(hit.snippet()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 执行搜索并返回结构化命中列表，供 {@link DeepResearchTool} 等组合工具复用（不再重复发起 HTTP 组装逻辑）
     *
     * @param query      搜索关键词
     * @param maxResults 最大返回条数（为空取默认值）
     * @return 结构化结果：失败时 error 为面向模型的提示文案
     */
    public SearchOutcome searchHits(String query, Integer maxResults) {
        try {
            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 结果条数容错处理
            int resultCount = maxResults != null ? maxResults : DEFAULT_MAX_RESULT;

            // 构建请求体
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("count", resultCount);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(endpoint, entity, String.class);
            if (response == null) {
                return SearchOutcome.failure("搜索失败：接口返回数据为空");
            }

            // 解析响应 JSON
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("data").path("webPages").path("value");
            if (!results.isArray()) {
                return SearchOutcome.failure("未查询到相关内容");
            }
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode item : results) {
                hits.add(new SearchHit(
                        item.path("name").asText(""),
                        item.path("snippet").asText(""),
                        item.path("url").asText("")));
            }
            return SearchOutcome.success(hits);

        } catch (RestClientException e) {
            return SearchOutcome.failure("搜索请求异常：接口调用失败");
        } catch (Exception e) {
            return SearchOutcome.failure("搜索解析异常：结果解析失败");
        }
    }

    /**
     * 单条搜索命中
     *
     * @param title   标题
     * @param snippet 摘要片段
     * @param url     原文地址（组合工具据此抓取正文）
     */
    public record SearchHit(String title, String snippet, String url) {
    }

    /**
     * 搜索结构化结果
     *
     * @param hits  命中列表（成功时非 null）
     * @param error 失败文案（成功时为 null）
     */
    public record SearchOutcome(List<SearchHit> hits, String error) {

        static SearchOutcome success(List<SearchHit> hits) {
            return new SearchOutcome(hits, null);
        }

        static SearchOutcome failure(String error) {
            return new SearchOutcome(List.of(), error);
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