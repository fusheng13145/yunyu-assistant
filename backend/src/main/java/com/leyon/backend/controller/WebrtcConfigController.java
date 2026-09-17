package com.leyon.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebRTC 配置接口
 * 向后端下发 ICE 服务器（STUN/TURN）配置，供前端创建 RTCPeerConnection 使用
 * 生产环境配置 TURN 服务器以穿透对称 NAT（环境变量 WEBRTC_ICE_SERVERS）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/webrtc")
public class WebrtcConfigController {

    private final ObjectMapper objectMapper;

    /** ICE 服务器 JSON（环境变量 WEBRTC_ICE_SERVERS，默认空数组） */
    @Value("${app.webrtc.ice-servers:[]}")
    private String iceServersJson;

    public WebrtcConfigController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 获取 WebRTC ICE 服务器配置
     * 返回结构：{ iceServers: [{ urls, username?, credential? }] }
     * 未配置时返回空数组，前端回退默认 STUN
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        List<Map<String, Object>> iceServers = parseIceServers();
        Map<String, Object> result = new HashMap<>();
        result.put("iceServers", iceServers);
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