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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 视频生成工具
 * 供 Spring AI 调用，按 OpenAI 兼容的 videos 异步任务契约工作：
 * generate_video 仅提交任务并立即返回任务号，query_video 单次查询任务状态与成片地址
 *
 * <p>拆成"提交 + 查询"两个工具的原因：本服务的工具调用是在对话流内同步执行的，
 * 视频生成动辄数十秒，若在单个工具内轮询等待会长时间占住该轮对话与 WebSocket 线程；
 * 交由模型自行"提交后稍后再查"可避免阻塞。
 *
 * <p>依赖 VIDEO_API_KEY：未配置时本工具不注册。
 * <p>注意：查询地址由提交地址拼接（{endpoint}/{jobId}），更换非 OpenAI 契约的服务商需同步调整本类解析逻辑。
 *
 * @author leyon
 */
@Component
@RequiresProperty("app.video.api-key")
public class VideoTool {

    /** 视频服务密钥 */
    @Value("${app.video.api-key}")
    private String apiKey;

    /** 视频任务提交接口地址 */
    @Value("${app.video.endpoint}")
    private String endpoint;

    /** 默认视频模型 */
    @Value("${app.video.model}")
    private String model;

    /** 任务号合法字符（防止把任务号拼成越权路径） */
    private static final String JOB_ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public VideoTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 注册 AI 工具回调：提交视频生成任务
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback generateVideoFunction() {
        return FunctionToolCallback
                .builder("generate_video", (Function<VideoRequest, String>) this::submit)
                .description("根据文字描述提交视频生成任务，立即返回任务号（不等待出片）。"
                        + "提交成功后应告知用户视频正在生成，并调用 query_video 查询结果。入参：prompt=画面描述")
                .inputType(VideoRequest.class)
                .build();
    }

    /**
     * 注册 AI 工具回调：查询视频任务
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback queryVideoFunction() {
        return FunctionToolCallback
                .builder("query_video", (Function<VideoQueryRequest, String>) this::query)
                .description("按任务号查询视频生成进度；完成后返回视频地址，请把地址以 Markdown 链接语法 [视频地址](地址) 回复给用户。"
                        + "入参：jobId=generate_video 返回的任务号")
                .inputType(VideoQueryRequest.class)
                .build();
    }

    /**
     * 提交视频生成任务
     *
     * @param request 生成请求参数
     * @return 任务号提示文本 / 错误提示
     */
    public String submit(VideoRequest request) {
        if (request == null || request.getPrompt() == null || request.getPrompt().isBlank()) {
            return "生成失败：视频描述不能为空";
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("prompt", request.getPrompt());

            String response = restTemplate.postForObject(endpoint, new HttpEntity<>(body, headers), String.class);
            if (response == null) {
                return "生成失败：视频接口无返回数据";
            }
            JsonNode root = objectMapper.readTree(response);
            JsonNode error = root.path("error").path("message");
            if (!error.isMissingNode()) {
                return "生成失败：" + error.asText("");
            }
            String jobId = jobIdOf(root);
            if (jobId.isBlank()) {
                return "生成失败：接口未返回任务号";
            }
            return "视频任务已提交，任务号：" + jobId + "；请稍后用 query_video 查询结果";
        } catch (RestClientException e) {
            return "生成失败：视频接口调用异常";
        } catch (Exception e) {
            return "生成失败：视频响应解析异常";
        }
    }

    /**
     * 查询视频任务状态
     *
     * @param request 查询请求参数
     * @return 状态文本（完成时含视频地址）/ 错误提示
     */
    public String query(VideoQueryRequest request) {
        if (request == null || request.getJobId() == null || !request.getJobId().trim().matches(JOB_ID_PATTERN)) {
            return "查询失败：任务号格式不合法";
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);
            String url = endpoint + "/" + request.getJobId().trim();

            String response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getBody();
            if (response == null) {
                return "查询失败：视频接口无返回数据";
            }
            JsonNode root = objectMapper.readTree(response);
            JsonNode error = root.path("error").path("message");
            if (!error.isMissingNode()) {
                return "查询失败：" + error.asText("");
            }
            String status = root.path("status").asText("");
            return switch (status) {
                case "completed", "succeeded" -> {
                    String url2 = videoUrlOf(root);
                    yield url2.isBlank() ? "视频已生成，但接口未返回可访问地址" : "视频已生成，地址：" + url2;
                }
                case "failed", "cancelled", "expired" ->
                        "视频生成未成功：" + root.path("failure_message").asText(status);
                default -> "视频仍在生成中（当前状态：" + (status.isBlank() ? "未知" : status) + "），请稍后再查询";
            };
        } catch (RestClientException e) {
            return "查询失败：视频接口调用异常";
        } catch (Exception e) {
            return "查询失败：视频响应解析异常";
        }
    }

    /**
     * 提取任务号，兼容 id / video_id 两种字段
     */
    private String jobIdOf(JsonNode root) {
        String id = root.path("id").asText("");
        return id.isBlank() ? root.path("video_id").asText("") : id;
    }

    /**
     * 提取成片地址，兼容 download_url / url / output_url 三种字段
     */
    private String videoUrlOf(JsonNode root) {
        for (String field : new String[]{"download_url", "url", "output_url"}) {
            String value = root.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * 视频生成请求参数
     */
    public static class VideoRequest {
        /** 画面描述 */
        private String prompt;

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }

    /**
     * 视频任务查询参数
     */
    public static class VideoQueryRequest {
        /** 任务号 */
        private String jobId;

        public String getJobId() {
            return jobId;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }
    }
}
