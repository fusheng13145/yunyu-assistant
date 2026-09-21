package com.leyon.backend.service;

import com.leyon.backend.entity.OutboundCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP PSTN 外呼网关实现（默认实现，P2-17）
 * 将外呼任务 POST 到 app.pstn.gateway-url（JSON：{taskId, assistantId, phoneNumber, callbackUrl}）；
 * 网关未配置时返回失败（任务置 FAILED，fail_reason="PSTN 网关未配置"）。
 *
 * @author leyon
 */
@Component
public class HttpPstnGateway implements PstnGateway {

    private static final Logger logger = LoggerFactory.getLogger(HttpPstnGateway.class);

    /** 网关地址（未配置则外呼失败降级） */
    @Value("${app.pstn.gateway-url:}")
    private String gatewayUrl;

    /** 本系统 PSTN 回调端点（网关回调更新任务状态，未配置使用占位提示） */
    @Value("${app.pstn.callback-url:}")
    private String callbackUrl;

    private final RestTemplate restTemplate;

    public HttpPstnGateway(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public PstnResult initiate(OutboundCall call) {
        if (!StringUtils.hasText(gatewayUrl)) {
            return new PstnResult(false, "PSTN 网关未配置");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("taskId", call.getId());
            body.put("assistantId", call.getAssistantId());
            body.put("phoneNumber", call.getPhoneNumber());
            body.put("callbackUrl", StringUtils.hasText(callbackUrl) ? callbackUrl : "/api/open/callbacks/pstn");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(gatewayUrl, entity, String.class);

            boolean ok = response.getStatusCode().is2xxSuccessful();
            if (!ok) {
                logger.warn("PSTN 网关发起外呼失败，taskId:{}，HTTP:{}", call.getId(), response.getStatusCode());
                return new PstnResult(false, "PSTN 网关返回异常（HTTP " + response.getStatusCode().value() + "）");
            }
            return new PstnResult(true, null);
        } catch (Exception e) {
            logger.error("PSTN 网关发起外呼异常，taskId:{}", call.getId(), e);
            return new PstnResult(false, "PSTN 网关调用失败");
        }
    }
}