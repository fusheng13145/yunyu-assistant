package com.leyon.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 语音合成(TTS)服务
 * 对接 OpenAI 语音接口，将文本转换为音频字节流
 *
 * @author leyon
 */
@Service
public class TTSService {

    /** 接口基础地址 */
    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    /** 接口密钥 */
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    /** TTS 模型名称 */
    @Value("${app.tts.model}")
    private String model;

    /** 默认发音人/音色 */
    @Value("${app.tts.speaker}")
    private String defaultSpeaker;

    /** 语音合成接口路径 */
    private static final String TTS_API_PATH = "/v1/audio/speech";

    private final RestTemplate restTemplate;

    public TTSService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 文本转语音
     *
     * @param text    待合成文本
     * @param speaker 发音人标识，传空则使用默认音色
     * @return 音频字节数组，异常/空入参返回空字节数组
     */
    public byte[] synthesize(String text, String speaker) {
        if (!StringUtils.hasText(text)) {
            return new byte[0];
        }

        try {
            // 构造请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 确定使用的音色
            String voice = StringUtils.hasText(speaker) ? speaker : defaultSpeaker;

            // 构造请求体
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", text,
                    "voice", voice
            );

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            String requestUrl = baseUrl + TTS_API_PATH;

            // 发起请求
            ResponseEntity<byte[]> response = restTemplate.postForEntity(requestUrl, requestEntity, byte[].class);
            byte[] bodyData = response.getBody();
            return bodyData != null ? bodyData : new byte[0];

        } catch (RestClientException e) {
            // 网络/接口调用异常
            return new byte[0];
        } catch (Exception e) {
            // 其他未知异常
            return new byte[0];
        }
    }
}