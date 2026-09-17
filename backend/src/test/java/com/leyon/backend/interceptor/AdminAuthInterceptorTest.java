package com.leyon.backend.interceptor;

import com.leyon.backend.entity.User;
import com.leyon.backend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员鉴权拦截器单元测试
 * 覆盖：未登录 / 普通用户 / 不存在用户被拒，管理员放行，OPTIONS 放行
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuthInterceptorTest {

    @Mock
    private UserService userService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setUp() throws Exception {
        // 被拒路径会写入 403 响应体
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    private AdminAuthInterceptor newInterceptor() {
        return new AdminAuthInterceptor(userService);
    }

    private User userWithRole(String role) {
        User u = new User();
        u.setId("u1");
        u.setRole(role);
        return u;
    }

    @Test
    void noUserId_rejected() throws Exception {
        AdminAuthInterceptor interceptor = newInterceptor();
        when(request.getAttribute("userId")).thenReturn(null);
        when(request.getMethod()).thenReturn("GET");
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(userService, never()).getById(anyString());
    }

    @Test
    void normalUser_rejected() throws Exception {
        AdminAuthInterceptor interceptor = newInterceptor();
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute("userId")).thenReturn("u1");
        when(userService.getById("u1")).thenReturn(userWithRole(User.ROLE_USER));
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
    }

    @Test
    void adminUser_allowed() throws Exception {
        AdminAuthInterceptor interceptor = newInterceptor();
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute("userId")).thenReturn("u1");
        when(userService.getById("u1")).thenReturn(userWithRole(User.ROLE_ADMIN));
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void userNotFound_rejected() throws Exception {
        AdminAuthInterceptor interceptor = newInterceptor();
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute("userId")).thenReturn("ghost");
        when(userService.getById("ghost")).thenReturn(null);
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
    }

    @Test
    void optionsPreflight_allowed() throws Exception {
        AdminAuthInterceptor interceptor = newInterceptor();
        when(request.getMethod()).thenReturn("OPTIONS");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}