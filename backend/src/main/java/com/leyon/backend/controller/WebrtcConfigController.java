package com.leyon.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.service.TurnCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebRTC 配置接口
 * 向后端下发 ICE 服务器（STUN/TURN）配置，供前端创建 RTCPeerConnection 使用
 * 静态条目来自环境变量 WEBRTC_ICE_SERVERS 的原样回显；配置了 TURN 共享密钥与 realm 时，
 * 另现签一条 REST 临时凭据（过期即失效，不再需要人工重生成并重启）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/webrtc")
public class WebrtcConfigController {

    private final ObjectMapper objectMapper;
    private final TurnCredentialService turnCredentials;

    /** ICE 服务器 JSON（环境变量 WEBRTC_ICE_SERVERS，默认空数组） */
    @Value("${app.webrtc.ice-servers:[]}")
    private String iceServersJson;

    public WebrtcConfigController(ObjectMapper objectMapper, TurnCredentialService turnCredentials) {
        this.objectMapper = objectMapper;
        this.turnCredentials = turnCredentials;
    }

    /**
     * 获取 WebRTC ICE 服务器配置
     * 返回结构：{ iceServers: [{ urls, username?, credential? }], turn: { signed, realm?, expiresAt?, ttlSec? } }
     * 未配置时返回空数组，前端回退默认 STUN
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config(HttpServletRequest request) {
        List<Map<String, Object>> iceServers = new ArrayList<>(parseIceServers());
        Map<String, Object> turn = new LinkedHashMap<>();
        turnCredentials.issue((String) request.getAttribute("userId"), Instant.now().getEpochSecond())
                .ifPresent(offer -> {
                    for (String url : offer.urls()) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("urls", url);
                        entry.put("username", offer.username());
                        entry.put("credential", offer.credential());
                        iceServers.add(entry);
                    }
                    turn.put("signed", true);
                    turn.put("realm", turnCredentials.getRealm());
                    turn.put("expiresAt", offer.expiresAt());
                    turn.put("ttlSec", turnCredentials.getTtlSec());
                });
        if (turn.isEmpty()) {
            turn.put("signed", false);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("iceServers", iceServers);
        result.put("turn", turn);
        return ApiResponse.success(result);
    }

    /**
     * 解析 ICE 服务器 JSON 配置，解析失败时返回空列表（不阻断建连）
     */
    private List<Map<String, Object>> parseIceServers() {
        if (!StringUtils.hasText(iceServersJson) || "[]".equals(iceServersJson.trim())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(iceServersJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}