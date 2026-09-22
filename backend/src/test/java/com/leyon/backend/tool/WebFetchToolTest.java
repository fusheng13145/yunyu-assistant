package com.leyon.backend.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 网页正文读取工具单元测试
 * 覆盖：SSRF 地址校验（内网/回环拒绝且不发请求）、重定向与错误状态拒绝、
 *       文档类型白名单、HTML 正文抽取、字节上限与字符截断
 * 用例统一用 IP 字面量（RFC 5737 TEST-NET 段）以避免依赖 DNS
 *
 * @author leyon
 */
class WebFetchToolTest {

    private static final String PUBLIC_URL = "http://203.0.113.10/page";

    private RestTemplate restTemplate;
    private WebFetchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        tool = new WebFetchTool();
        setField("restTemplate", restTemplate);
        setField("maxBytes", 100_000);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = WebFetchTool.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tool, value);
    }

    /**
     * 让 mock 的 RestTemplate 真实执行工具内部的响应抽取逻辑
     */
    private void stubResponse(MockClientHttpResponse response) {
        when(restTemplate.execute(anyString(), eq(HttpMethod.GET), any(), any(ResponseExtractor.class)))
                .thenAnswer(invocation -> {
                    ResponseExtractor<String> extractor = invocation.getArgument(3);
                    return extractor.extractData(response);
                });
    }

    private MockClientHttpResponse html(String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_HTML);
        return response;
    }

    private WebFetchTool.WebFetchRequest request(String url) {
        WebFetchTool.WebFetchRequest req = new WebFetchTool.WebFetchRequest();
        req.setUrl(url);
        return req;
    }

    @Test
    void blankUrl_failsWithoutRequest() {
        assertThat(tool.fetch(request("  "))).contains("网页地址不能为空");
        assertThat(tool.fetch(null)).contains("网页地址不能为空");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void internalAddresses_rejectedWithoutRequest() {
        assertThat(tool.fetch(request("http://127.0.0.1/admin"))).contains("不允许指向本机或内网地址");
        assertThat(tool.fetch(request("http://192.168.1.1/"))).contains("不允许指向本机或内网地址");
        assertThat(tool.fetch(request("http://169.254.169.254/latest/meta-data/"))).contains("不允许指向本机或内网地址");
        assertThat(tool.fetch(request("ftp://203.0.113.10/x"))).contains("不允许指向本机或内网地址");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void redirectsAndErrorStatus_refused() {
        MockClientHttpResponse redirect = new MockClientHttpResponse(new byte[0], HttpStatus.FOUND);
        redirect.getHeaders().setLocation(java.net.URI.create("http://127.0.0.1/secret"));
        stubResponse(redirect);
        assertThat(tool.fetch(request(PUBLIC_URL))).contains("不跟随重定向");

        stubResponse(new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND));
        assertThat(tool.fetch(request(PUBLIC_URL))).contains("404");
    }

    @Test
    void unsupportedContentType_refused() {
        MockClientHttpResponse pdf = new MockClientHttpResponse("binary".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        pdf.getHeaders().setContentType(MediaType.APPLICATION_PDF);
        stubResponse(pdf);

        assertThat(tool.fetch(request(PUBLIC_URL))).contains("不支持的文档类型");
    }

    @Test
    void html_extractsVisibleTextOnly() {
        stubResponse(html("""
                <html><head><title>标题</title><script>var x=1;</script></head>
                <body><style>a{}</style><h1>欢迎</h1><p>正文&amp;内容&nbsp;完成</p></body></html>
                """));

        String result = tool.fetch(request(PUBLIC_URL));

        assertThat(result).contains("欢迎").contains("正文&内容 完成").doesNotContain("var x=1").doesNotContain("<h1>");
    }

    @Test
    void plainText_keptAsIsAndTruncatedForModel() {
        stubResponse(text("A".repeat(3000)));
        assertThat(tool.fetch(request(PUBLIC_URL))).hasSize(2000 + "…(正文过长，已截断)".length());

        stubResponse(text("   "));
        assertThat(tool.fetch(request(PUBLIC_URL))).contains("无可见文本");
    }

    @Test
    void bodyReadCappedByConfiguredBytes() throws Exception {
        setField("maxBytes", 1024);
        stubResponse(text("B".repeat(5000)));

        assertThat(tool.fetch(request(PUBLIC_URL))).hasSize(1024);
    }

    @Test
    void requestException_returnsFriendlyText() {
        when(restTemplate.execute(anyString(), eq(HttpMethod.GET), any(), any(ResponseExtractor.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThat(tool.fetch(request(PUBLIC_URL))).isEqualTo("抓取失败：网页请求异常");
    }

    private MockClientHttpResponse text(String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        return response;
    }
}
