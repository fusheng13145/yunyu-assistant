package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 视频生成工具单元测试
 * 覆盖：提交任务取任务号（兼容 id/video_id）、查询状态分支（完成/进行中/失败）、
 *       任务号格式校验（防路径注入）、接口异常与解析异常
 *
 * @author leyon
 */
class VideoToolTest {

    private static final String ENDPOINT = "https://vid.test/v1/videos";

    private RestTemplate restTemplate;
    private VideoTool tool;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        tool = new VideoTool(new ObjectMapper());
        setField("restTemplate", restTemplate);
        setField("apiKey", "k1");
        setField("endpoint", ENDPOINT);
        setField("model", "sora-2");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = VideoTool.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tool, value);
    }

    private VideoTool.VideoRequest submitRequest(String prompt) {
        VideoTool.VideoRequest req = new VideoTool.VideoRequest();
        req.setPrompt(prompt);
        return req;
    }

    private VideoTool.VideoQueryRequest queryRequest(String jobId) {
        VideoTool.VideoQueryRequest req = new VideoTool.VideoQueryRequest();
        req.setJobId(jobId);
        return req;
    }

    @Test
    void submit_blankPrompt_failsWithoutCallingProvider() {
        assertThat(tool.submit(submitRequest(" "))).contains("视频描述不能为空");
        assertThat(tool.submit(null)).contains("视频描述不能为空");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void submit_returnsJobIdAndBuildsBody() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenReturn("{\"id\":\"vid_123\",\"status\":\"queued\"}");

        assertThat(tool.submit(submitRequest("海浪"))).contains("vid_123");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForObject(eq(ENDPOINT), captor.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((HttpEntity<?>) captor.getValue()).getBody();
        assertThat(body).containsEntry("model", "sora-2").containsEntry("prompt", "海浪");
    }

    @Test
    void submit_supportsVideoIdFieldAndSurfacesProviderError() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenReturn("{\"video_id\":\"v9\"}");
        assertThat(tool.submit(submitRequest("海浪"))).contains("v9");

        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenReturn("{\"error\":{\"message\":\"model overloaded\"}}");
        assertThat(tool.submit(submitRequest("海浪"))).isEqualTo("生成失败：model overloaded");

        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn("{}");
        assertThat(tool.submit(submitRequest("海浪"))).contains("未返回任务号");
    }

    @Test
    void query_completed_returnsDownloadUrl() {
        when(restTemplate.exchange(eq(ENDPOINT + "/vid_123"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"completed\",\"download_url\":\"https://cdn.test/v.mp4\"}"));

        assertThat(tool.query(queryRequest("vid_123"))).contains("https://cdn.test/v.mp4");
    }

    @Test
    void query_alternativeUrlFieldsAndStatuses() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"succeeded\",\"url\":\"https://cdn.test/a.mp4\"}"));
        assertThat(tool.query(queryRequest("j1"))).contains("https://cdn.test/a.mp4");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"completed\"}"));
        assertThat(tool.query(queryRequest("j1"))).contains("未返回可访问地址");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"processing\"}"));
        assertThat(tool.query(queryRequest("j1"))).contains("仍在生成中");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"failed\",\"failure_message\":\"内容审核未通过\"}"));
        assertThat(tool.query(queryRequest("j1"))).contains("内容审核未通过");
    }

    @Test
    void query_rejectsIllegalJobId() {
        assertThat(tool.query(queryRequest("../../etc/passwd"))).contains("任务号格式不合法");
        assertThat(tool.query(queryRequest(""))).contains("任务号格式不合法");
        assertThat(tool.query(null)).contains("任务号格式不合法");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void providerExceptionAndBadJson_returnFriendlyText() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect reset"));
        assertThat(tool.submit(submitRequest("海浪"))).isEqualTo("生成失败：视频接口调用异常");

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("broken"));
        assertThat(tool.query(queryRequest("j1"))).isEqualTo("查询失败：视频响应解析异常");
    }
}
