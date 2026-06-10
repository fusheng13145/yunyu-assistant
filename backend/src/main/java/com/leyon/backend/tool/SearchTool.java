package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class SearchTool {

    @Value("${app.search.api-key}")
    private String apiKey;

    @Value("${app.search.endpoint}")
    private String endpoint;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public SearchTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Bean
    public ToolCallback webSearchFunction() {
        return FunctionToolCallback.builder("web_search", (Function<SearchRequest, String>) this::search)
                .description("Perform internet search to get up-to-date information")
                .inputType(SearchRequest.class)
                .build();
    }

    public String search(SearchRequest request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            int count = request.getMaxResults() != null ? request.getMaxResults() : 5;
            Map<String, Object> body = new HashMap<>();
            body.put("query", request.getQuery());
            body.put("count", count);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String response = restTemplate.postForObject(endpoint, entity, String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("data").path("webPages").path("value");

            StringBuilder sb = new StringBuilder();
            for (JsonNode item : results) {
                sb.append(item.path("name").asText()).append(": ");
                sb.append(item.path("snippet").asText()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error searching: " + e.getMessage();
        }
    }

    public static class SearchRequest {
        private String query;
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
