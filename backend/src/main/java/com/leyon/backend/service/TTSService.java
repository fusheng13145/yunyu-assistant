package com.leyon.backend.service;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class TTSService {

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${app.tts.model}")
    private String model;

    @Value("${app.tts.speaker}")
    private String defaultSpeaker;

    private final RestTemplate restTemplate;

    public TTSService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public byte[] synthesize(String text, String speaker) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            String voice = speaker != null ? speaker : defaultSpeaker;
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", text,
                    "voice", voice
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<byte[]> response = restTemplate.postForEntity(
                    baseUrl + "/v1/audio/speech", entity, byte[].class);

            return response.getBody();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
