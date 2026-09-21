package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.mapper.ApiAppMapper;
import com.leyon.backend.mapper.WebhookDeliveryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webhook 回调服务（P2-17 Webhook 回调）
 * 将事件投递到第三方应用配置的 webhook_url：
 * - 异步投递（自建线程池），失败重试（指数退避，最多 app.webhook.retry-max-attempts 次）；
 * - 投递记录落库 webhook_deliveries（PENDING/SUCCESS/FAILED + next_retry_at 供定时扫描重投）；
 * - 请求头携带 X-Webhook-Signature = HMAC-SHA256(webhook_secret, body)（secret 为空不签名）。
 *
 * @author leyon
 */
@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    /** 签名请求头名称 */
    public static final String HEADER_SIGNATURE = "X-Webhook-Signature";

    private final ApiAppMapper apiAppMapper;
    private final WebhookDeliveryMapper webhookDeliveryMapper;
    private final WebhookProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public WebhookService(ApiAppMapper apiAppMapper,
                          WebhookDeliveryMapper webhookDeliveryMapper,
                          WebhookProperties properties,
                          RestTemplate restTemplate,
                          ObjectMapper objectMapper) {
        this.apiAppMapper = apiAppMapper;
        this.webhookDeliveryMapper = webhookDeliveryMapper;
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        // 投递线程池（异步，避免阻塞主业务链路）
        this.executor = Executors.newFixedThreadPool(2);
    }

    /**
     * 派发事件：应用未配 webhook_url 或全局关闭则跳过；否则落库 PENDING 并异步投递
     *
     * @param eventType 事件类型（WebhookDelivery.EVENT_*）
     * @param appId     第三方应用ID
     * @param payload   事件负载（Map 转 JSON 存库）
     */
    public void dispatch(String eventType, String appId, Map<String, Object> payload) {
        if (!properties.isEnabled() || !StringUtils.hasText(appId)) {
            return;
        }
        ApiApp app = apiAppMapper.selectById(appId);
        if (app == null || !StringUtils.hasText(app.getWebhookUrl())) {
            return;
        }
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            payloadJson = "{}";
        }

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setEventType(eventType);
        delivery.setAppId(appId);
        delivery.setPayload(payloadJson);
        delivery.setStatus(WebhookDelivery.STATUS_PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextRetryAt(LocalDateTime.now());
        delivery.setCreatedAt(LocalDateTime.now());
        webhookDeliveryMapper.insert(delivery);

        final String deliveryId = delivery.getId();
        executor.submit(() -> deliver(deliveryId));
    }

    /**
     * 投递单条记录（含重试逻辑）：成功→SUCCESS；失败→attempt+1，未超次 → FAILED + next_retry_at 指数退避
     */
    public void deliver(String deliveryId) {
        WebhookDelivery delivery = webhookDeliveryMapper.selectById(deliveryId);
        if (delivery == null) {
            return;
        }
        ApiApp app = apiAppMapper.selectById(delivery.getAppId());
        if (app == null) {
            return;
        }
        String webhookUrl = app.getWebhookUrl();
        if (!StringUtils.hasText(webhookUrl)) {
            markFailed(delivery, null);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // 签名：HMAC-SHA256(secret, body)（secret 为空不签名）
            String body = delivery.getPayload() == null ? "{}" : delivery.getPayload();
            if (StringUtils.hasText(app.getWebhookSecret())) {
                headers.set(HEADER_SIGNATURE, sign(app.getWebhookSecret(), body));
            }
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            var response = restTemplate.postForEntity(webhookUrl, entity, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                delivery.setStatus(WebhookDelivery.STATUS_SUCCESS);
                delivery.setAttemptCount((delivery.getAttemptCount() == null ? 0 : delivery.getAttemptCount()) + 1);
                delivery.setNextRetryAt(null);
                webhookDeliveryMapper.updateById(delivery);
            } else {
                markFailed(delivery, "HTTP " + response.getStatusCode().value());
            }
        } catch (Exception e) {
            logger.warn("Webhook 投递失败，deliveryId:{}，原因:{}", deliveryId, e.getMessage());
            markFailed(delivery, e.getMessage());
        }
    }

    /**
     * 标记投递失败并按指数退避安排下次重试（超次则保持 FAILED 不再重试）
     */
    private void markFailed(WebhookDelivery delivery, String reason) {
        int attempts = (delivery.getAttemptCount() == null ? 0 : delivery.getAttemptCount()) + 1;
        delivery.setAttemptCount(attempts);
        if (attempts < properties.getRetryMaxAttempts()) {
            delivery.setStatus(WebhookDelivery.STATUS_FAILED);
            long delay = properties.getRetryBaseDelayMs() * (1L << Math.max(0, attempts - 1));
            delivery.setNextRetryAt(LocalDateTime.now().plusNanos(delay * 1_000_000));
        } else {
            delivery.setStatus(WebhookDelivery.STATUS_FAILED);
            delivery.setNextRetryAt(null); // 达上限，不再自动重试
            logger.warn("Webhook 投递重试耗尽，deliveryId:{}，事件:{}，原因:{}", delivery.getId(), delivery.getEventType(), reason);
        }
        webhookDeliveryMapper.updateById(delivery);
    }

    /**
     * 查询待重试的投递记录（status FAILED/PENDING 且未达重试上限且到时间）
     */
    public java.util.List<WebhookDelivery> listRetryable(LocalDateTime now) {
        return webhookDeliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                .in(WebhookDelivery::getStatus, WebhookDelivery.STATUS_FAILED, WebhookDelivery.STATUS_PENDING)
                .lt(WebhookDelivery::getNextRetryAt, now)
                .lt(WebhookDelivery::getAttemptCount, properties.getRetryMaxAttempts()));
    }

    /**
     * HMAC-SHA256 签名（hex 小写）
     */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}