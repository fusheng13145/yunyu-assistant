package com.leyon.backend.service;

import com.leyon.backend.entity.OutboundCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP PSTN 外呼网关实现单元测试（P2-17 开放 OpenAPI 语音外呼）
 * 覆盖：网关未配置返回失败、成功返回 success、调用异常返回失败
 *
 * @author leyon
 */
class HttpPstnGatewayTest {

    private RestTemplate restTemplate;
    private HttpPstnGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        gateway = new HttpPstnGateway(restTemplate);
    }

    private void setGatewayField(String name, String value) throws Exception {
        Field f = HttpPstnGateway.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(gateway, value);
    }

    private OutboundCall task() {
        OutboundCall call = new OutboundCall();
        call.setId("t1");
        call.setAssistantId("a1");
        call.setPhoneNumber("10086");
        return call;
    }

    @Test
    void initiate_gatewayUrlMissing_returnsFailure() throws Exception {
        setGatewayField("gatewayUrl", "");
        PstnGateway.PstnResult result = gateway.initiate(task());
        assertThat(result.success()).isFalse();
        assertThat(result.failReason()).contains("未配置");
    }

    @Test
    void initiate_success_returnsOk() throws Exception {
        setGatewayField("gatewayUrl", "https://gw.example.com/call");
        when(restTemplate.postForEntity(eq("https://gw.example.com/call"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));
        PstnGateway.PstnResult result = gateway.initiate(task());
        assertThat(result.success()).isTrue();
    }

    @Test
    void initiate_gatewayError_returnsFailure() throws Exception {
        setGatewayField("gatewayUrl", "https://gw.example.com/call");
        when(restTemplate.postForEntity(eq("https://gw.example.com/call"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("err"));
        PstnGateway.PstnResult result = gateway.initiate(task());
        assertThat(result.success()).isFalse();
        assertThat(result.failReason()).contains("HTTP 500");
    }

    @Test
    void initiate_exception_returnsFailure() throws Exception {
        setGatewayField("gatewayUrl", "https://gw.example.com/call");
        when(restTemplate.postForEntity(eq("https://gw.example.com/call"), any(), eq(String.class)))
                .thenThrow(new RuntimeException("connection refused"));
        PstnGateway.PstnResult result = gateway.initiate(task());
        assertThat(result.success()).isFalse();
    }
}