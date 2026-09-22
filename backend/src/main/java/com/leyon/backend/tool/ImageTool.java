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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 图像生成工具
 * 供 Spring AI 调用，按 OpenAI 兼容的 images 接口以文生图
 *
 * <p>依赖 IMAGE_API_KEY：未配置时本工具不注册。
 * <p>仅回传图片 URL，不回传 base64，避免超长二进制串污染模型上下文。
 *
 * @author leyon
 */
@Component
@RequiresProperty("app.image.api-key")
public class ImageTool {

    /** 图像服务密钥 */
    @Value("${app.image.api-key}")
    private String apiKey;

    /** 图像生成接口地址 */
    @Value("${app.image.endpoint}")
    private String endpoint;

    /** 默认图像模型 */
    @Value("${app.image.model}")
    private String model;

    /** 单次最多生成张数 */
    private static final int MAX_IMAGE_COUNT = 4;

    /** 默认图片尺寸 */
    private static final String DEFAULT_SIZE = "1024x1024";

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ImageTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 文生图耗时明显长于普通接口调用，读取超时放宽到 120 秒
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 注册 AI 工具回调：文生图
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback generateImageFunction() {
        return FunctionToolCallback
                .builder("generate_image", (Function<ImageRequest, String>) this::generate)
                .description("根据文字描述生成图片，返回可访问的图片地址。"
                        + "生成成功后请把地址原样以 Markdown 图片语法 ![图片描述](地址) 回复给用户，便于前端直接展示。"
                        + "入参：prompt=画面描述，size=尺寸(如 1024x1024/1792x1024，可省略)，count=张数(1-4，可省略)")
                .inputType(ImageRequest.class)
                .build();
    }

    /**
     * 执行文生图
     *
     * @param request 生成请求参数
     * @return 图片地址文本 / 错误提示
     */
    public String generate(ImageRequest request) {
        if (request == null || request.getPrompt() == null || request.getPrompt().isBlank()) {
            return "生成失败：图片描述不能为空";
        }
        int count = request.getCount() == null ? 1
                : Math.min(Math.max(request.getCount(), 1), MAX_IMAGE_COUNT);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("prompt", request.getPrompt());
            body.put("n", count);
            body.put("size", request.getSize() == null || request.getSize().isBlank()
                    ? DEFAULT_SIZE : request.getSize().trim());

            String response = restTemplate.postForObject(endpoint, new HttpEntity<>(body, headers), String.class);
            if (response == null) {
                return "生成失败：图像接口无返回数据";
            }
            JsonNode root = objectMapper.readTree(response);
            JsonNode error = root.path("error").path("message");
            if (!error.isMissingNode()) {
                return "生成失败：" + error.asText("");
            }
            JsonNode items = root.path("data");
            if (!items.isArray() || items.isEmpty()) {
                return "生成失败：接口未返回图片数据";
            }

            List<String> results = new ArrayList<>();
            for (JsonNode item : items) {
                String url = item.path("url").asText("");
                // 部分服务默认返回 b64_json，不入上下文，只提示模型改走 URL 模式
                results.add(url.isBlank() ? "（接口返回了 base64 数据，未透传图片地址）" : url);
            }
            return String.join("\n", results);

        } catch (RestClientException e) {
            return "生成失败：图像接口调用异常";
        } catch (Exception e) {
            return "生成失败：图像响应解析异常";
        }
    }

    /**
     * 图像生成请求参数
     */
    public static class ImageRequest {
        /** 画面描述 */
        private String prompt;
        /** 图片尺寸，如 1024x1024 */
        private String size;
        /** 生成张数，1-4 */
        private Integer count;

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }

        public String getSize() {
            return size;
        }

        public void setSize(String size) {
            this.size = size;
        }

        public Integer getCount() {
            return count;
        }

        public void setCount(Integer count) {
            this.count = count;
        }
    }
}
