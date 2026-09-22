package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.service.TokenBlacklistService;
import com.leyon.backend.service.UserService;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改密接口单元测试
 * 覆盖：/api/auth/** 为拦截器放行路径时需自行从 Authorization 头解析用户、无令牌时拒绝
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private AuthController authController;

    private final Map<String, String> body = Map.of("oldPassword", "Old1234", "newPassword", "New12345");

    @Test
    void changePassword_resolvesUserFromAuthorizationHeader() {
        // 拦截器未注入 userId（放行路径），令牌有效
        when(request.getAttribute("userId")).thenReturn(null);
        when(request.getHeader("Authorization")).thenReturn("Bearer access-token");
        when(jwtUtil.validateToken("access-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("access-token")).thenReturn("u-1");
        when(userService.changePassword("u-1", "Old1234", "New12345")).thenReturn(true);

        ApiResponse<Void> result = authController.changePassword(body, request);

        assertThat(result.getCode()).isEqualTo(200);
        verify(userService).changePassword("u-1", "Old1234", "New12345");
    }

    @Test
    void changePassword_withoutValidToken_isRejected() {
        when(request.getAttribute("userId")).thenReturn(null);
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");
        when(jwtUtil.validateToken("expired-token")).thenReturn(false);

        ApiResponse<Void> result = authController.changePassword(body, request);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("未登录");
        verify(userService, never()).changePassword(anyString(), anyString(), anyString());
    }

    @Test
    void changePassword_wrongOldPassword_returnsError() {
        when(request.getAttribute("userId")).thenReturn("u-1");
        when(userService.changePassword(eq("u-1"), any(), any())).thenReturn(false);

        ApiResponse<Void> result = authController.changePassword(body, request);

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMessage()).isEqualTo("原密码不正确");
    }
}
