package com.leyon.backend.task;

import com.leyon.backend.entity.WebhookDelivery;
import com.leyon.backend.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Webhook 失败投递重试定时任务（P2-17 Webhook 回调）
 * 周期性扫描 next_retry_at 到期的 FAILED/PENDING 记录重新投递（指数退避由 WebhookService 编排）
 *
 * @author leyon
 */
@Component
public class WebhookDeliveryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);

    private final WebhookService webhookService;

    public WebhookDeliveryScheduler(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @Scheduled(fixedDelayString = "${app.webhook.retry-interval-ms:60000}")
    public void retryPendingDeliveries() {
        try {
            List<WebhookDelivery> retryable = webhookService.listRetryable(LocalDateTime.now());
            if (retryable.isEmpty()) {
                return;
            }
            for (WebhookDelivery delivery : retryable) {
                webhookService.deliver(delivery.getId());
            }
            logger.info("Webhook 重试扫描完成，重投 {} 条", retryable.size());
        } catch (Exception e) {
            logger.error("Webhook 重试扫描异常", e);
        }
    }
}