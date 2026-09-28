package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.service.AssistantPolicy;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.ModelCatalog;
import com.leyon.backend.service.OrgService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手接口的成本参数闸门单测（v2.54 · 候选 ㉔）
 * <p>
 * 锁的是"越界的配置进不了库"这一侧（钳制边界本身在 {@code AssistantPolicyTest}）：
 * 请求-响应通道要给得出原因，所以 HTTP 侧是拒绝而不是静默改写；且必须在写库动作之前拒掉，
 * 否则一次非法请求就已经把超限值留下了。
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantControllerTest {

    private static final String USER = "u-owner";

    @Mock
    private AssistantService assistantService;
    @Mock
    private OrgService orgService;
    @Mock
    private HttpServletRequest request;

    private AssistantController controller;

    @BeforeEach
    void setUp() {
        controller = new AssistantController(assistantService, orgService, new AssistantPolicy(new ModelCatalog()));
        when(request.getAttribute("userId")).thenReturn(USER);
    }

    private Assistant ownedAssistant() {
        Assistant a = new Assistant();
        a.setId("a1");
        a.setUserId(USER);
        return a;
    }

    @Test
    void createRejectsModelOutsideCatalogBeforeAnyWrite() {
        Assistant body = new Assistant();
        body.setName("越界助手");
        body.setModelName("gpt-9-mega");

        ApiResponse<Assistant> response = controller.create(body, request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("清单");
        verify(assistantService, never()).create(any());
    }

    @Test
    void createRejectsMaxTokensAboveCapAndNamesTheCap() {
        Assistant body = new Assistant();
        body.setName("越界助手");
        body.setMaxTokens(999_999);

        ApiResponse<Assistant> response = controller.create(body, request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains(String.valueOf(AssistantPolicy.MAX_OUTPUT_TOKENS));
        verify(assistantService, never()).create(any());
    }

    @Test
    void createRejectsOversizedPersonality() {
        Assistant body = new Assistant();
        body.setName("越界助手");
        body.setPersonality("啊".repeat(AssistantPolicy.MAX_PERSONALITY_CHARS + 1));

        assertThat(controller.create(body, request).getCode()).isEqualTo(400);
        verify(assistantService, never()).create(any());
    }

    @Test
    void createAcceptsValuesWithinPolicyAndAssignsOwner() {
        Assistant body = new Assistant();
        body.setName("合规助手");
        body.setModelName("qwen-plus");
        body.setTemperature(1.0);
        body.setMaxTokens(2048);

        when(assistantService.create(any(Assistant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ApiResponse<Assistant> response = controller.create(body, request);

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData().getUserId()).isEqualTo(USER);
    }

    @Test
    void updateRejectsInvalidTemperatureAfterOwnershipCheckButBeforeWriting() {
        when(assistantService.getById("a1")).thenReturn(ownedAssistant());
        Assistant body = new Assistant();
        body.setId("a1");
        body.setTemperature(2.5);

        ApiResponse<Void> response = controller.update(body, request);

        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("温度");
        verify(assistantService, never()).update(any());
    }

    @Test
    void updateDoesNotWriteForNonOwner() {
        Assistant other = ownedAssistant();
        other.setUserId("u-other");
        when(assistantService.getById("a1")).thenReturn(other);

        Assistant body = new Assistant();
        body.setId("a1");
        body.setTemperature(2.5);

        // 鉴权在钳制之前：越权者连"这个助手的模型参数是否合法"都问不出来
        assertThatThrownBy(() -> controller.update(body, request))
                .isInstanceOf(ForbiddenException.class);
        verify(assistantService, never()).update(any());
    }
}
