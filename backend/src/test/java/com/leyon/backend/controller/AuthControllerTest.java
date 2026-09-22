package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.User;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证接口单元测试
 * 覆盖：/api/auth/** 为拦截器放行路径时需自行从 Authorization 头解析用户、无令牌时拒绝、
 * 注册密码长度规则、刷新令牌的字段契约与账号存在性校验
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

    @Test
    void register_rejectsPasswordShorterThanSix() {
        ApiResponse<Map<String, String>> result = authController.register(
                Map.of("username", "alice", "password", "ab123"));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("6");
        verify(userService, never()).register(anyString(), anyString());
    }

    @Test
    void refresh_returnsRoleAndRequiresExistingAccount() {
        when(jwtUtil.isTokenType("refresh-token", JwtUtil.TOKEN_TYPE_REFRESH)).thenReturn(true);
        when(jwtUtil.validateToken("refresh-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("refresh-token")).thenReturn("u-1");
        when(jwtUtil.getJtiFromToken("refresh-token")).thenReturn("jti-1");
        User user = new User();
        user.setId("u-1");
        user.setUsername("alice");
        user.setRole(User.ROLE_ADMIN);
        when(userService.getById("u-1")).thenReturn(user);

        ApiResponse<Map<String, String>> result = authController.refresh(Map.of("refreshToken", "refresh-token"));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsEntry("role", User.ROLE_ADMIN)
                .containsEntry("username", "alice")
                .containsEntry("userId", "u-1");
        verify(tokenBlacklistService).blacklist(eq("jti-1"), anyLong());
    }

    @Test
    void refresh_forDeletedAccount_issuesNoToken() {
        when(jwtUtil.isTokenType("refresh-token", JwtUtil.TOKEN_TYPE_REFRESH)).thenReturn(true);
        when(jwtUtil.validateToken("refresh-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("refresh-token")).thenReturn("u-gone");
        when(userService.getById("u-gone")).thenReturn(null);

        ApiResponse<Map<String, String>> result = authController.refresh(Map.of("refreshToken", "refresh-token"));

        assertThat(result.getCode()).isEqualTo(400);
        verify(jwtUtil, never()).generateToken(anyString(), anyString());
        verify(jwtUtil, never()).generateRefreshToken(anyString(), anyString());
    }
}
