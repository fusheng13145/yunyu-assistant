package com.leyon.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class KnowledgeService {

    @Value("${app.ragflow.api-key}")
    private String apiKey;

    @Value("${app.ragflow.endpoint}")
    private String endpoint;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String queryKnowledgeBase(String question, List<String> datasetIds) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("question", question);
            body.put("dataset_ids", datasetIds);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint + "/api/v1/retrieval", request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode chunks = root.path("data").path("chunks");

            StringBuilder context = new StringBuilder();
            for (JsonNode chunk : chunks) {
                String content = chunk.path("content").asText();
                if (!content.isEmpty()) {
                    context.append(content).append("\n");
                }
            }

            return context.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
