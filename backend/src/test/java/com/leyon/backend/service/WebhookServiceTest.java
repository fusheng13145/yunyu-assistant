package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.mapper.ApiAppMapper;
import com.leyon.backend.mapper.WebhookDeliveryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Webhook 回调服务单元测试（P2-17 Webhook 回调）
 * 覆盖：无 webhook_url 跳过、dispatch 落库、deliver 成功、失败重试次数与 next_retry_at、签名
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookServiceTest {

    @Mock
    private ApiAppMapper apiAppMapper;
    @Mock
    private WebhookDeliveryMapper webhookDeliveryMapper;
    @Mock
    private RestTemplate restTemplate;

    private WebhookProperties properties;
    private WebhookService webhookService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WebhookDelivery.class);
        objectMapper = new ObjectMapper();
        properties = new WebhookProperties();
        properties.setEnabled(true);
        properties.setRetryMaxAttempts(3);
        properties.setRetryBaseDelayMs(5000);
        properties.setRetryIntervalMs(60000);
        webhookService = new WebhookService(apiAppMapper, webhookDeliveryMapper, properties, restTemplate, objectMapper);
        lenient().when(apiAppMapper.selectById("app-1")).thenReturn(appWithWebhook("app-1", "https://example.com/hook", "secret-1"));
    }

    private ApiApp appWithWebhook(String id, String url, String secret) {
        ApiApp app = new ApiApp();
        app.setId(id);
        app.setUserId("u1");
        app.setWebhookUrl(url);
        app.setWebhookSecret(secret);
        return app;
    }

    private WebhookDelivery delivery(String id, String status, int attempts) {
        WebhookDelivery d = new WebhookDelivery();
        d.setId(id);
        d.setAppId("app-1");
        d.setEventType(WebhookDelivery.EVENT_CALL_COMPLETED);
        d.setPayload("{}");
        d.setStatus(status);
        d.setAttemptCount(attempts);
        d.setNextRetryAt(LocalDateTime.now());
        return d;
    }

    @Test
    void dispatch_noWebhookUrl_skips() {
        when(apiAppMapper.selectById("app-2"))
                .thenReturn(appWithWebhook("app-2", null, null));
        webhookService.dispatch(WebhookDelivery.EVENT_CALL_COMPLETED, "app-2", java.util.Map.of("k", "v"));
        org.mockito.Mockito.verify(webhookDeliveryMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<WebhookDelivery>any());
    }

    @Test
    void dispatch_disabled_skips() {
        properties.setEnabled(false);
        webhookService.dispatch(WebhookDelivery.EVENT_CALL_COMPLETED, "app-1", java.util.Map.of("k", "v"));
        org.mockito.Mockito.verify(webhookDeliveryMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<WebhookDelivery>any());
    }

    @Test
    void dispatch_persistsPendingRecord() {
        org.mockito.Mockito.doAnswer(inv -> {
            WebhookDelivery d = inv.getArgument(0);
            d.setId("d1");
            return 1;
        }).when(webhookDeliveryMapper).insert(org.mockito.ArgumentMatchers.<WebhookDelivery>any());
        webhookService.dispatch(WebhookDelivery.EVENT_CALL_COMPLETED, "app-1", java.util.Map.of("k", "v"));
        org.mockito.Mockito.verify(webhookDeliveryMapper)
                .insert(org.mockito.ArgumentMatchers.<WebhookDelivery>any());
    }

    @Test
    void deliver_success_marksSuccess() {
        WebhookDelivery d = delivery("d1", WebhookDelivery.STATUS_PENDING, 0);
        when(webhookDeliveryMapper.selectById("d1")).thenReturn(d);
        when(restTemplate.postForEntity(eq("https://example.com/hook"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));
        webhookService.deliver("d1");
        assertThat(d.getStatus()).isEqualTo(WebhookDelivery.STATUS_SUCCESS);
        assertThat(d.getAttemptCount()).isEqualTo(1);
        verify(webhookDeliveryMapper).updateById(d);
    }

    @Test
    void deliver_failure_schedulesRetry() {
        WebhookDelivery d = delivery("d1", WebhookDelivery.STATUS_PENDING, 0);
        when(webhookDeliveryMapper.selectById("d1")).thenReturn(d);
        when(restTemplate.postForEntity(eq("https://example.com/hook"), any(), eq(String.class)))
                .thenThrow(new RuntimeException("connect timeout"));
        webhookService.deliver("d1");
        assertThat(d.getStatus()).isEqualTo(WebhookDelivery.STATUS_FAILED);
        assertThat(d.getAttemptCount()).isEqualTo(1);
        assertThat(d.getNextRetryAt()).isNotNull();
    }

    @Test
    void deliver_failureExhaustRetries_keepsFailedNoRetry() {
        WebhookDelivery d = delivery("d1", WebhookDelivery.STATUS_FAILED, 3);
        when(webhookDeliveryMapper.selectById("d1")).thenReturn(d);
        when(restTemplate.postForEntity(eq("https://example.com/hook"), any(), eq(String.class)))
                .thenThrow(new RuntimeException("boom"));
        webhookService.deliver("d1");
        assertThat(d.getAttemptCount()).isEqualTo(4);
        assertThat(d.getNextRetryAt()).isNull(); // 达上限不再重试
    }

    @Test
    void listRetryable_returnsEligible() {
        when(webhookDeliveryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(delivery("d1", WebhookDelivery.STATUS_FAILED, 1)));
        List<WebhookDelivery> retryable = webhookService.listRetryable(LocalDateTime.now());
        assertThat(retryable).hasSize(1);
    }

    @Test
    void sign_producesHmacSha256Hex() {
        String sig = WebhookService.sign("secret", "body");
        assertThat(sig).isNotBlank();
        assertThat(sig).matches("[0-9a-f]{64}");
    }
}