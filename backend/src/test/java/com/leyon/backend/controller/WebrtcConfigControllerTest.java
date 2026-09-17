package com.leyon.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebRTC 配置接口单元测试
 * 覆盖：未配置返回空数组、配置 JSON 正确下发、非法 JSON 降级空数组
 *
 * @author leyon
 */
class WebrtcConfigControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebrtcConfigController newController(String iceServersJson) throws Exception {
        WebrtcConfigController controller = new WebrtcConfigController(objectMapper);
        Field f = WebrtcConfigController.class.getDeclaredField("iceServersJson");
        f.setAccessible(true);
        f.set(controller, iceServersJson);
        return controller;
    }

    @Test
    void emptyConfig_returnsEmptyList() throws Exception {
        WebrtcConfigController controller = newController("[]");
        ApiResponse<Map<String, Object>> result = controller.config();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("iceServers")).isEqualTo(List.of());
    }

    @Test
    void blankConfig_returnsEmptyList() throws Exception {
        WebrtcConfigController controller = newController("  ");
        assertThat((List<?>) controller.config().getData().get("iceServers")).isEmpty();
    }

    @Test
    void customTurnConfig_parsed() throws Exception {
        String json = "[{\"urls\":\"turn:turn.example.com:3478\",\"username\":\"u\",\"credential\":\"p\"},{\"urls\":\"stun:stun.example.com:3478\"}]";
        WebrtcConfigController controller = newController(json);
        List<Map<String, Object>> servers = (List<Map<String, Object>>) controller.config().getData().get("iceServers");
        assertThat(servers).hasSize(2);
        assertThat(servers.get(0)).containsEntry("urls", "turn:turn.example.com:3478");
        assertThat(servers.get(0)).containsEntry("username", "u");
        assertThat(servers.get(1)).containsEntry("urls", "stun:stun.example.com:3478");
    }

    @Test
    void invalidJson_returnsEmptyList() throws Exception {
        WebrtcConfigController controller = newController("not-json{{{");
        assertThat((List<?>) controller.config().getData().get("iceServers")).isEmpty();
    }
}