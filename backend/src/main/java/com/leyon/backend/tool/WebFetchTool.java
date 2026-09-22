package com.leyon.backend.tool;

import com.leyon.backend.util.ExternalUrlValidator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;

/**
 * 网页正文读取工具
 * 供 Spring AI 调用，抓取用户/模型给出的公网网页并抽取正文文本
 *
 * <p>安全约束（防 SSRF）：
 * 1. 地址先过 ExternalUrlValidator，拒绝指向本机/内网/云元数据；
 * 2. 关闭 HTTP 自动重定向并对 3xx 直接拒绝，避免"校验一个地址、请求另一个地址"的重绑定绕过；
 * 3. 仅接受 text/html、text/plain、application/json 三类响应，且按字节上限截断读取。
 *
 * <p>本工具无需第三方密钥即可工作，因此默认关闭（app.webfetch.enabled=false），
 * 由运维显式开启；开启前请确认出网策略（手册 6.6 残余风险）。
 *
 * @author leyon
 */
@Component
@RequiresProperty(value = "app.webfetch.enabled", expected = "true")
public class WebFetchTool {

    /** 单次读取的最大字节数（超出即截断，防止大文件占满内存与上下文） */
    @Value("${app.webfetch.max-bytes}")
    private int maxBytes;

    /** 正文返回给模型的最大字符数，与 ChatService 的工具结果截断阈值对齐 */
    private static final int MAX_TEXT_CHARS = 2000;

    private RestTemplate restTemplate;

    public WebFetchTool() {
        // 关闭跨协议自动跳转，并对 3xx 直接拒绝，保证"实际请求的地址"就是"通过校验的地址"
        // 关闭跨协议自动跳转，并对 3xx 直接拒绝，保证"实际请求的地址"就是"通过校验的地址"
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 注册 AI 工具回调：读取网页正文
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback fetchWebpageFunction() {
        return FunctionToolCallback
                .builder("fetch_webpage", (Function<WebFetchRequest, String>) this::fetch)
                .description("读取指定网页的正文文本，用于获取已知地址的页面内容（不支持需要登录的页面）。入参：url=完整网页地址")
                .inputType(WebFetchRequest.class)
                .build();
    }

    /**
     * 执行网页抓取与正文抽取
     *
     * @param request 抓取请求参数
     * @return 网页正文 / 错误提示
     */
    public String fetch(WebFetchRequest request) {
        if (request == null || request.getUrl() == null || request.getUrl().isBlank()) {
            return "抓取失败：网页地址不能为空";
        }
        String url = request.getUrl().trim();
        try {
            ExternalUrlValidator.requirePublicHttpUrl(url);
        } catch (IllegalArgumentException e) {
            return "抓取失败：" + e.getMessage();
        }

        try {
            return restTemplate.execute(url, HttpMethod.GET, null, response -> {
                int status = response.getStatusCode().value();
                if (status >= 300 && status < 400) {
                    return "抓取失败：目标地址发生跳转，出于安全考虑不跟随重定向";
                }
                if (status >= 400) {
                    return "抓取失败：目标地址返回状态码 " + status;
                }
                String contentType = response.getHeaders().getContentType() == null
                        ? "" : response.getHeaders().getContentType().toString().toLowerCase();
                if (!contentType.contains("text/html") && !contentType.contains("text/plain")
                        && !contentType.contains("application/json")) {
                    return "抓取失败：不支持的文档类型（" + contentType + "），仅支持网页/纯文本/JSON";
                }
                byte[] bytes = readCapped(response);
                String raw = new String(bytes, StandardCharsets.UTF_8);
                return contentType.contains("text/html") ? truncate(extractText(raw)) : truncate(raw);
            });
        } catch (RestClientException e) {
            return "抓取失败：网页请求异常";
        } catch (Exception e) {
            return "抓取失败：网页内容解析异常";
        }
    }

    /**
     * 按配置上限读取响应体（readNBytes 读满上限即停，不等待完整响应）
     */
    private byte[] readCapped(ClientHttpResponse response) throws IOException {
        int limit = Math.max(1024, maxBytes);
        try (InputStream in = response.getBody()) {
            return in.readNBytes(limit);
        }
    }

    /**
     * 轻量正文抽取：去脚本/样式/标签并还原常见实体，不做 DOM 解析
     */
    private String extractText(String html) {
        return html
                .replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /**
     * 超出模型上下文预算时截断
     */
    private String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "抓取结果：页面无可见文本";
        }
        return text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS) + "…(正文过长，已截断)";
    }

    /**
     * 网页抓取请求参数
     */
    public static class WebFetchRequest {
        /** 完整网页地址 */
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
