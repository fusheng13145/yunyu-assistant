package com.leyon.backend.controller;

import com.leyon.backend.model.ApiResponse;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledges")
public class KnowledgeController {

    @Value("${app.ragflow.api-key}")
    private String ragflowApiKey;

    @Value("${app.ragflow.endpoint}")
    private String ragflowEndpoint;

    @GetMapping
    public ApiResponse<Map<String, String>> getConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("apiKey", "Bearer " + ragflowApiKey);
        config.put("endpoint", ragflowEndpoint);
        return ApiResponse.success(config);
    }
}
