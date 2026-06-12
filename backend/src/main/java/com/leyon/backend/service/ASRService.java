package com.leyon.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 语音转文字服务(ASR)
 * 对接 OpenAI 系列语音识别接口，实现音频转文本
 *
 * @author leyon
 */
@Service
public class ASRService {

    /** 接口基础地址 */
    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    /** 接口密钥 */
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    /** 语音识别模型名称 */
    @Value("${app.asr.model}")
    private String model;

    /** 音频默认文件名 */
    private static final String AUDIO_FILE_NAME = "audio.wav";
    /** 接口路径 */
    private static final String ASR_API_PATH = "/v1/audio/transcriptions";

    private final RestTemplate restTemplate;

    public ASRService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 音频转文字
     *
     * @param audioData 音频字节数组
     * @return 识别文本结果 / 错误提示
     */
    public String transcribe(byte[] audioData) {
        // 前置参数校验
        if (audioData == null || audioData.length == 0) {
            return "语音识别失败：音频数据不能为空";
        }

        try {
            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(apiKey);

            // 封装音频文件资源
            ByteArrayResource audioResource = new ByteArrayResource(audioData) {
                @Override
                public String getFilename() {
                    return AUDIO_FILE_NAME;
                }
            };

            // 构建表单请求体
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", audioResource);
            body.add("model", model);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // 发起请求
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + ASR_API_PATH, requestEntity, String.class);

            // 响应判空
            String result = response.getBody();
            return result != null ? result : "语音识别失败：接口返回数据为空";

        } catch (RestClientException e) {
            return "请求异常：调用语音识别接口失败，" + e.getMessage();
        } catch (Exception e) {
            return "处理异常：" + e.getMessage();
        }
    }
}