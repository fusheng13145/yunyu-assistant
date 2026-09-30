package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.service.UsageStatsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量统计接口单元测试（v2.67：口径外移后，控制器只剩"主体来自认证属性"这一条契约）
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

    private static final String USER = "u1";
    private static final String ORG = "org-1";

    @Mock
    private UsageStatsService usageStatsService;

    private HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", USER);
        return request;
    }

    @Test
    void subjectIsTheAuthenticatedUserAndPayloadIsPassedThroughUnchanged() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("callCount", 3L);
        when(usageStatsService.usage(USER, "week", null)).thenReturn(payload);

        ApiResponse<Map<String, Object>> response =
                new StatsController(usageStatsService).usage("week", null, request());

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isEqualTo(payload);
    }

    @Test
    void orgScopeParameterReachesTheServiceWhereMembershipIsChecked() {
        when(usageStatsService.usage(USER, "day", ORG)).thenReturn(Map.of("scope", "org"));

        ApiResponse<Map<String, Object>> response =
                new StatsController(usageStatsService).usage("day", ORG, request());

        assertThat(response.getData()).containsEntry("scope", "org");
        verify(usageStatsService).usage(USER, "day", ORG);
    }

    @Test
    void nonMemberDenialPropagatesInsteadOfBecomingAnEmptyReport() {
        // 吞掉 403 会返回一份"零用量"报告：对外数字看起来正常，实则越权请求成功了
        when(usageStatsService.usage(USER, "week", ORG)).thenThrow(new ForbiddenException("无权访问该组织资源"));

        StatsController controller = new StatsController(usageStatsService);
        assertThatThrownBy(() -> controller.usage("week", ORG, request()))
                .isInstanceOf(ForbiddenException.class);
    }
}
