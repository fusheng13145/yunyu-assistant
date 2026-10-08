package com.leyon.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.service.TurnCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebRTC 配置接口单元测试
 * 覆盖：未配置返回空数组、配置 JSON 正确下发、非法 JSON 降级空数组、
 * TURN 签发条目的追加与"未配置时零行为变更"（`turn.signed=false`）
 *
 * @author leyon
 */
class WebrtcConfigControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebrtcConfigController newController(String iceServersJson) throws Exception {
        return newController(iceServersJson, "", "", 7200);
    }

    private WebrtcConfigController newController(String iceServersJson, String secret, String realm, long ttl)
            throws Exception {
        WebrtcConfigController controller =
                new WebrtcConfigController(objectMapper, new TurnCredentialService(secret, realm, ttl));
        Field f = WebrtcConfigController.class.getDeclaredField("iceServersJson");
        f.setAccessible(true);
        f.set(controller, iceServersJson);
        return controller;
    }

    private MockHttpServletRequest requestAs(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.setAttribute("userId", userId);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> servers(ApiResponse<Map<String, Object>> result) {
        return (List<Map<String, Object>>) result.getData().get("iceServers");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> turn(ApiResponse<Map<String, Object>> result) {
        return (Map<String, Object>) result.getData().get("turn");
    }

    @Test
    void emptyConfig_returnsEmptyList() throws Exception {
        WebrtcConfigController controller = newController("[]");
        ApiResponse<Map<String, Object>> result = controller.config(requestAs("user_admin"));
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("iceServers")).isEqualTo(List.of());
    }

    @Test
    void blankConfig_returnsEmptyList() throws Exception {
        WebrtcConfigController controller = newController("  ");
        assertThat(servers(controller.config(requestAs("user_admin")))).isEmpty();
    }

    @Test
    void customTurnConfig_parsed() throws Exception {
        String json = "[{\"urls\":\"turn:turn.example.com:3478\",\"username\":\"u\",\"credential\":\"p\"},{\"urls\":\"stun:stun.example.com:3478\"}]";
        WebrtcConfigController controller = newController(json);
        List<Map<String, Object>> iceServers = servers(controller.config(requestAs("user_admin")));
        assertThat(iceServers).hasSize(2);
        assertThat(iceServers.get(0)).containsEntry("urls", "turn:turn.example.com:3478");
        assertThat(iceServers.get(0)).containsEntry("username", "u");
        assertThat(iceServers.get(1)).containsEntry("urls", "stun:stun.example.com:3478");
    }

    @Test
    void invalidJson_returnsEmptyList() throws Exception {
        WebrtcConfigController controller = newController("not-json{{{");
        assertThat(servers(controller.config(requestAs("user_admin")))).isEmpty();
    }

    /** 反向锚点：没配签发密钥时响应里既没有凭据也不能多出条目（静态回显路径零行为变更） */
    @Test
    void signingDisabled_appendsNothingAndReportsUnsigned() throws Exception {
        WebrtcConfigController controller = newController("[{\"urls\":\"stun:stun.l.google.com:19302\"}]");
        ApiResponse<Map<String, Object>> result = controller.config(requestAs("user_admin"));
        assertThat(servers(result)).hasSize(1);
        assertThat(turn(result)).containsEntry("signed", false);
        assertThat(turn(result)).doesNotContainKey("credential");
    }

    @Test
    void signingEnabled_appendsTwoUrisWithSameFreshCredential() throws Exception {
        WebrtcConfigController controller = newController("[]", "test-secret", "turn.example.com", 3600);
        ApiResponse<Map<String, Object>> result = controller.config(requestAs("user_admin"));
        List<Map<String, Object>> iceServers = servers(result);
        assertThat(iceServers).hasSize(2);
        assertThat(iceServers).allSatisfy(entry -> {
            assertThat((String) entry.get("urls")).startsWith("turn:turn.example.com:3478?transport=");
            assertThat((String) entry.get("username")).endsWith(":user_admin");
            assertThat((String) entry.get("credential")).isNotBlank();
        });
        assertThat(iceServers.get(0).get("credential")).isEqualTo(iceServers.get(1).get("credential"));
        assertThat(turn(result)).containsEntry("signed", true)
                .containsEntry("realm", "turn.example.com")
                .containsEntry("ttlSec", 3600L);
        assertThat((Long) turn(result).get("expiresAt"))
                .isGreaterThan(java.time.Instant.now().getEpochSecond());
    }

    /** 半途配置（只给密钥）＝不签发，与"没配"同形但不静默：控制器侧读到的仍是 false */
    @Test
    void halfConfigured_signsNothing() throws Exception {
        WebrtcConfigController controller = newController("[]", "test-secret", "", 3600);
        assertThat(turn(controller.config(requestAs("user_admin")))).containsEntry("signed", false);
    }

    /** 未鉴权到身份（属性缺失）时不给匿名凭据 */
    @Test
    void missingUserId_signsNothing() throws Exception {
        WebrtcConfigController controller = newController("[]", "test-secret", "turn.example.com", 3600);
        ApiResponse<Map<String, Object>> result = controller.config(requestAs(null));
        assertThat(servers(result)).isEmpty();
        assertThat(turn(result)).containsEntry("signed", false);
    }
}
