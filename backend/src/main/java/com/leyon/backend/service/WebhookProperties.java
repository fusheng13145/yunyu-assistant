package com.leyon.backend.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Webhook 回调配置（P2-17）
 * 对应 application.yaml 的 app.webhook.*
 *
 * @author leyon
 */
@Component
@ConfigurationProperties(prefix = "app.webhook")
public class WebhookProperties {

    /** 全局开关（false 时不投递 Webhook） */
    private boolean enabled = true;
    /** 失败重试最大次数 */
    private int retryMaxAttempts = 3;
    /** 重试基础延迟（毫秒），指数退避 baseDelay * 2^(attempt-1) */
    private long retryBaseDelayMs = 5000;
    /** 失败重试扫描周期（毫秒） */
    private long retryIntervalMs = 60000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public void setRetryBaseDelayMs(long retryBaseDelayMs) {
        this.retryBaseDelayMs = retryBaseDelayMs;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }
}