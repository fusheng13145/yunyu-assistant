package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.ApiApp;
import com.leyon.backend.service.ApiAppService;
import com.leyon.backend.service.OpenApiDenialMeter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 拒绝台账只读接口单测（v2.50 · 候选 ㉜）
 * <p>
 * 这里锁的是可见性口径，不是计数（计数在 {@code OpenApiDenialMeterTest}）：
 * ①属主只看到自己应用的行，别人的应用 id 连"存在被拒"这件事都不透露；
 * ②无法归属的行（无 Key / 无效 Key / 限流）全体可见——爆破针对的不是某个应用，
 * 最该看见的人看不到就等于没做，而这一格只有次数、不含任何凭据片段；
 * ③窗口参数越界按保留期截断后回显，前端不必再抄一份上限。
 * 其余接口（创建/改能力/吊销）由服务层测试与真机冒烟覆盖。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAppControllerTest {

    private static final String OWNER = "user-owner";
    private static final String OTHER = "user-other";
    private static final String MY_APP = "app-mine";
    private static final String THEIR_APP = "app-theirs";

    @Mock
    private ApiAppService apiAppService;
    @Mock
    private HttpServletRequest request;

    private final OpenApiDenialMeter denialMeter = new OpenApiDenialMeter();

    private ApiAppController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiAppController(apiAppService, denialMeter);
        when(apiAppService.listByUser(OWNER)).thenReturn(List.of(app(MY_APP)));
        when(apiAppService.listByUser(OTHER)).thenReturn(List.of(app(THEIR_APP)));
    }

    private ApiApp app(String id) {
        ApiApp app = new ApiApp();
        app.setId(id);
        return app;
    }

    /** 以指定属主走一次读数，返回行 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsFor(String userId, int hours) {
        when(request.getAttribute("userId")).thenReturn(userId);
        ApiResponse<Map<String, Object>> response = controller.denials(hours, request);
        assertThat(response.getCode()).isEqualTo(200);
        return (List<Map<String, Object>>) response.getData().get("rows");
    }

    @Test
    @DisplayName("属主只拿到自己应用的行：别人的应用被拒过多少次都不透露")
    void ownerSeesOnlyOwnAppRows() {
        denialMeter.record(OpenApiDenialMeter.Kind.SCOPE_DENIED, MY_APP);
        denialMeter.record(OpenApiDenialMeter.Kind.SCOPE_DENIED, THEIR_APP);

        assertThat(rowsFor(OWNER, 24)).singleElement()
                .extracting(row -> row.get("app")).isEqualTo(MY_APP);
        assertThat(rowsFor(OTHER, 24)).singleElement()
                .extracting(row -> row.get("app")).isEqualTo(THEIR_APP);
    }

    @Test
    @DisplayName("无法归属的行全体可见，且 app 字段为 null（不是字符串 \"unknown\" 泄漏内部哨兵）")
    void unknownRowsAreVisibleToEveryone() {
        denialMeter.record(OpenApiDenialMeter.Kind.KEY_INVALID, null);

        List<Map<String, Object>> mine = rowsFor(OWNER, 24);
        assertThat(mine).singleElement().extracting(row -> row.get("app")).isNull();
        assertThat(mine.get(0).get("kind")).isEqualTo("KEY_INVALID");
        assertThat(mine.get(0).get("kindLabel")).isEqualTo(OpenApiDenialMeter.Kind.KEY_INVALID.label());
        assertThat(mine.get(0).get("count")).isEqualTo(1L);
    }

    @Test
    @DisplayName("吊销后自己的历史行随归属查询一起消失：行不报错、也不冒充别人的")
    void revokedAppRowsDropOutOfOwnerQuery() {
        denialMeter.record(OpenApiDenialMeter.Kind.SCOPE_DENIED, MY_APP);
        when(apiAppService.listByUser(OWNER)).thenReturn(List.of());

        assertThat(rowsFor(OWNER, 24)).isEmpty();
    }

    @Test
    @DisplayName("窗口参数越界时回显截断值：0 与超大值都按保留期收口")
    void windowIsClampedAndEchoed() {
        when(request.getAttribute("userId")).thenReturn(OWNER);
        assertThat(controller.denials(0, request).getData().get("windowHours"))
                .isEqualTo(OpenApiDenialMeter.clampWindow(0));
        assertThat(controller.denials(9999, request).getData().get("windowHours"))
                .isEqualTo(OpenApiDenialMeter.MAX_RETAINED_HOURS);
    }

    /**
     * 创建响应必须由出口显式构造（v2.64 · C-131）。
     * <p>
     * 实体自 v2.64 起把凭据字段的出站一律掐掉（含一次性明文 {@code appKey} 与签名密钥），
     * 所以这条判据锁的是另一端：**掐抑制不等于把交付路径一起掐掉**。v2.17 就是因为漏了这一步，
     * Webhook 签名恒不生效却无人报错。整颗实体的响应形状同时被禁掉——那是"新端点顺手 return 实体
     * 就把密钥发出去"的那条路。
     */
    @Test
    @DisplayName("创建响应是出口显式构造的一次性载荷：给得出明文，给不出库里存的东西")
    @SuppressWarnings("unchecked")
    void createResponseIsExplicitlyBuiltOneTimePayload() throws Exception {
        ApiApp created = new ApiApp();
        created.setId("app-new");
        created.setAppName("冒烟应用");
        created.setAppKey("plain-key-shown-once");
        created.setAppKeyHash("6db7c1a5 hashed-never-returned");
        created.setWebhookSecret("webhook-secret-shown-once");
        created.setWebhookUrl("https://example.com/hook");
        created.setScopes("chat");
        created.setEnabled(ApiApp.ENABLED);
        when(apiAppService.create(eq(OWNER), eq("冒烟应用"), isNull(), eq("chat"))).thenReturn(created);
        when(request.getAttribute("userId")).thenReturn(OWNER);

        Object data = controller.create(Map.of("appName", "冒烟应用", "scopes", "chat"), request).getData();

        assertThat(data).as("载荷必须是出口显式构造的 Map，不是整颗实体")
                .isInstanceOf(Map.class);
        Map<String, Object> payload = (Map<String, Object>) data;
        assertThat(payload)
                .containsEntry("appKey", "plain-key-shown-once")
                .containsEntry("webhookSecret", "webhook-secret-shown-once")
                .doesNotContainKey("appKeyHash")
                .doesNotContainKey("isDeleted");

        // 出口显式给的明文确实进得了 JSON：实体上的 @JsonIgnore 管不到 Map 的条目
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules().writeValueAsString(payload);
        assertThat(json).contains("plain-key-shown-once").contains("webhook-secret-shown-once")
                .doesNotContain("6db7c1a5");
    }
}
