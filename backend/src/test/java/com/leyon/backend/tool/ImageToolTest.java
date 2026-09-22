package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 图像生成工具单元测试
 * 覆盖：入参校验、请求体组装（张数钳制/尺寸默认）、URL 透传、base64 兜底、
 *       服务商错误信息透出、接口异常与响应解析异常
 *
 * @author leyon
 */
class ImageToolTest {

    private static final String ENDPOINT = "https://img.test/v1/images/generations";

    private RestTemplate restTemplate;
    private ImageTool tool;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        tool = new ImageTool(new ObjectMapper());
        setField("restTemplate", restTemplate);
        setField("apiKey", "k1");
        setField("endpoint", ENDPOINT);
        setField("model", "dall-e-3");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ImageTool.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tool, value);
    }

    private ImageTool.ImageRequest request(String prompt, String size, Integer count) {
        ImageTool.ImageRequest req = new ImageTool.ImageRequest();
        req.setPrompt(prompt);
        req.setSize(size);
        req.setCount(count);
        return req;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedBody() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate, atLeastOnce()).postForObject(eq(ENDPOINT), captor.capture(), eq(String.class));
        java.util.List<Object> sent = captor.getAllValues();
        return (Map<String, Object>) ((HttpEntity<?>) sent.get(sent.size() - 1)).getBody();
    }

    @Test
    void blankPrompt_failsWithoutCallingProvider() {
        assertThat(tool.generate(request("  ", null, null))).contains("图片描述不能为空");
        assertThat(tool.generate(null)).contains("图片描述不能为空");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void success_returnsUrlsAndBuildsOpenAiCompatibleBody() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn(
                "{\"data\":[{\"url\":\"https://cdn.test/a.png\"},{\"url\":\"https://cdn.test/b.png\"}]}");

        String result = tool.generate(request("一只猫", "1792x1024", 2));

        assertThat(result).contains("https://cdn.test/a.png").contains("https://cdn.test/b.png");
        Map<String, Object> body = capturedBody();
        assertThat(body).containsEntry("model", "dall-e-3")
                .containsEntry("prompt", "一只猫")
                .containsEntry("size", "1792x1024")
                .containsEntry("n", 2);
    }

    @Test
    void countAndSize_fallBackToDefaultsAndClamp() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenReturn("{\"data\":[{\"url\":\"https://cdn.test/a.png\"}]}");

        tool.generate(request("猫", null, 99));
        assertThat(capturedBody()).containsEntry("n", 4).containsEntry("size", "1024x1024");

        tool.generate(request("猫", "  ", 0));
        assertThat(capturedBody()).containsEntry("n", 1);
    }

    @Test
    void providerReturnsBase64Only_doesNotLeakIntoContext() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenReturn("{\"data\":[{\"b64_json\":\"AAAAbase64...\"}]}");

        assertThat(tool.generate(request("猫", null, null))).contains("base64").doesNotContain("AAAAbase64");
    }

    @Test
    void providerError_surfacesMessage() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenReturn("{\"error\":{\"message\":\"quota exceeded\"}}");

        assertThat(tool.generate(request("猫", null, null))).isEqualTo("生成失败：quota exceeded");
    }

    @Test
    void missingDataArray_fails() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn("{\"created\":1}");
        assertThat(tool.generate(request("猫", null, null))).contains("未返回图片数据");

        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn(null);
        assertThat(tool.generate(request("猫", null, null))).contains("无返回数据");
    }

    @Test
    void providerExceptionAndBadJson_returnFriendlyText() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect reset"));
        assertThat(tool.generate(request("猫", null, null))).isEqualTo("生成失败：图像接口调用异常");

        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn("not-json");
        assertThat(tool.generate(request("猫", null, null))).isEqualTo("生成失败：图像响应解析异常");
    }
}
