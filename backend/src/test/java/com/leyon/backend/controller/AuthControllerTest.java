package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.User;
import com.leyon.backend.service.InviteCodeService;
import com.leyon.backend.service.TokenBlacklistService;
import com.leyon.backend.service.UserService;
import com.leyon.backend.util.ClientIpResolver;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

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
 * 请求头凭据只认 access 令牌（C-63）、注册密码长度规则、刷新令牌的字段契约与账号存在性校验、
 * 登录交给锁定层的来源地址（v2.44）、登录标识的长度上限按三态最宽列而非用户名上限（v2.89）
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
    private InviteCodeService inviteCodeService;

    @Mock
    private HttpServletRequest request;

    /** 真身（不 mock）：本批要锁的正是"控制器把哪一个地址交给登录锁定"，桩会把它自身也桩掉 */
    @Spy
    private ClientIpResolver clientIpResolver = new ClientIpResolver(false, 1);

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void injectBlacklistMargin() {
        // @Value 不参与 @InjectMocks：余量在此显式注入，判据才可断言精确 TTL
        org.springframework.test.util.ReflectionTestUtils.setField(authController, "blacklistTtlMarginMs", 60_000L);
    }

    private final Map<String, String> body = Map.of("oldPassword", "Old1234", "newPassword", "New12345");

    @Test
    void changePassword_resolvesUserFromAuthorizationHeader() {
        // 拦截器未注入 userId（放行路径），头里带的是有效 access 令牌
        when(request.getAttribute("userId")).thenReturn(null);
        when(request.getHeader("Authorization")).thenReturn("Bearer access-token");
        when(jwtUtil.validateAccessToken("access-token")).thenReturn(true);
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
        when(jwtUtil.validateAccessToken("expired-token")).thenReturn(false);

        ApiResponse<Void> result = authController.changePassword(body, request);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("未登录");
        verify(userService, never()).changePassword(anyString(), anyString(), anyString());
    }

    @Test
    void logout_blacklistsValidAccessToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer access-token");
        when(jwtUtil.validateAccessToken("access-token")).thenReturn(true);
        when(jwtUtil.getJtiFromToken("access-token")).thenReturn("jti-1");
        when(jwtUtil.getRemainingValidityMs("access-token")).thenReturn(900_000L);

        authController.logout(null, request);

        // C-95 收口：黑名单 TTL = 令牌剩余期 + 余量，不再写死 7 天（7 天 = 604_800_000ms）
        verify(tokenBlacklistService).blacklist(eq("jti-1"), eq(960_000L));
        verify(tokenBlacklistService, never()).blacklist(eq("jti-1"), eq(604_800_000L));
    }

    @Test
    void logout_expiredTokenStillBlacklistedWithMarginFloor() {
        // 已过期令牌剩余期为 0：TTL 仍须 ≥ 余量（黑名单服务内部还有 1 秒下限），不能出现 0/负值
        when(request.getHeader("Authorization")).thenReturn("Bearer access-token");
        when(jwtUtil.validateAccessToken("access-token")).thenReturn(true);
        when(jwtUtil.getJtiFromToken("access-token")).thenReturn("jti-expired");
        when(jwtUtil.getRemainingValidityMs("access-token")).thenReturn(0L);

        authController.logout(null, request);

        verify(tokenBlacklistService).blacklist(eq("jti-expired"), eq(60_000L));
    }

    @Test
    void logout_ignoresRefreshTokenPresentedAsBearer() {
        // C-63：请求头只认 access 令牌；refresh 令牌须经 body.refreshToken 字段撤销，不能在这里误命中
        when(request.getHeader("Authorization")).thenReturn("Bearer refresh-token");
        when(jwtUtil.validateAccessToken("refresh-token")).thenReturn(false);

        authController.logout(null, request);

        verify(tokenBlacklistService, never()).blacklist(anyString(), anyLong());
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
        verify(userService, never()).register(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void register_passesEmailPhoneAndInviteCodeThroughToService() {
        User user = new User();
        user.setId("u-9");
        user.setUsername("alice");
        user.setRole(User.ROLE_USER);
        when(userService.register("alice", "abc12345", "alice@example.com", "13800000000", "CODE123"))
                .thenReturn(user);
        when(jwtUtil.generateToken("u-9", "alice")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("u-9", "alice")).thenReturn("refresh-token");

        ApiResponse<Map<String, String>> result = authController.register(Map.of(
                "username", "alice", "password", "abc12345",
                "email", "alice@example.com", "phone", "13800000000", "inviteCode", "CODE123"));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsEntry("userId", "u-9");
        // 归一化与查重都在服务层，控制器只做透传：在这里顺手 trim/toLowerCase 会造出第二套判据
        verify(userService).register("alice", "abc12345", "alice@example.com", "13800000000", "CODE123");
    }

    @Test
    void register_withoutEmailAndPhone_passesNulls() {
        User user = new User();
        user.setId("u-10");
        user.setUsername("alice");
        user.setRole(User.ROLE_USER);
        when(userService.register("alice", "abc12345", null, null, null)).thenReturn(user);

        ApiResponse<Map<String, String>> result = authController.register(
                Map.of("username", "alice", "password", "abc12345"));

        assertThat(result.getCode()).isEqualTo(200);
        verify(userService).register("alice", "abc12345", null, null, null);
    }

    @Test
    void registerConfig_reportsWhetherInviteCodeRequired() {
        when(inviteCodeService.inviteRequired()).thenReturn(true);
        when(inviteCodeService.mode()).thenReturn(InviteCodeService.MODE_INVITE);

        ApiResponse<Map<String, Object>> result = authController.registerConfig();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).containsEntry("inviteRequired", true)
                .containsEntry("mode", InviteCodeService.MODE_INVITE);
    }

    @Test
    void registerConfig_modeAndInviteRequiredNeverDisagree() {
        // 前端按 inviteRequired 决定要不要显示码框，按 mode 决定文案：两者来自同一次判定，
        // 分成两次读取就可能出现"文案说放开、表单要码"
        when(inviteCodeService.inviteRequired()).thenReturn(false);
        when(inviteCodeService.mode()).thenReturn(InviteCodeService.MODE_OPEN);

        ApiResponse<Map<String, Object>> result = authController.registerConfig();

        assertThat(result.getData()).containsEntry("inviteRequired", false)
                .containsEntry("mode", InviteCodeService.MODE_OPEN);
    }

    @Test
    void refresh_returnsRoleAndRequiresExistingAccount() {
        when(jwtUtil.isTokenType("refresh-token", JwtUtil.TOKEN_TYPE_REFRESH)).thenReturn(true);
        when(jwtUtil.validateToken("refresh-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("refresh-token")).thenReturn("u-1");
        when(jwtUtil.getJtiFromToken("refresh-token")).thenReturn("jti-1");
        when(jwtUtil.getRemainingValidityMs("refresh-token")).thenReturn(604_800_000L);
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
        // C-95 收口：轮换出的旧 refresh 令牌，黑名单 TTL = 剩余期 + 余量
        verify(tokenBlacklistService).blacklist(eq("jti-1"), eq(604_800_000L + 60_000L));
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

    /**
     * v2.44：登录必须把"服务端能证明的来源"交给锁定判定，否则来源维度形同虚设。
     * 这里用真实 resolver（显式取生产默认形态：不信任代理头），而不是 mock，
     * 否则"控制器自己读 XFF 第一段"这种回归没人拦得住。
     */
    @Test
    void login_passesServerProvenClientIp_notForgedForwardedFor() {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        loginRequest.setRemoteAddr("203.0.113.7");
        loginRequest.addHeader("X-Forwarded-For", "1.1.1.1");
        when(userService.login("alice", "abc12345", "203.0.113.7"))
                .thenReturn(Map.of("token", "access-token", "refreshToken", "refresh-token"));

        ApiResponse<Map<String, String>> result = authController.login(
                Map.of("username", "alice", "password", "abc12345"), loginRequest);

        assertThat(result.getCode()).isEqualTo(200);
        verify(userService).login("alice", "abc12345", "203.0.113.7");
    }

    /**
     * v2.89：登录输入框接受三态标识后，历史写死的"用户名 ≤32"上限会把长邮箱挡在控制器里，
     * 而报错指向用户名——用户填的是邮箱，却被告知用户名太短/太长。上限必须容纳最宽的那一列。
     */
    @Test
    void login_acceptsEmailIdentifierLongerThanUsernameBound() {
        String longEmail = "very.long.local.part.for.identifier.login.test@example.com";
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        loginRequest.setRemoteAddr("203.0.113.7");
        when(userService.login(longEmail, "abc12345", "203.0.113.7"))
                .thenReturn(Map.of("token", "access-token", "refreshToken", "refresh-token"));

        ApiResponse<Map<String, String>> result = authController.login(
                Map.of("username", longEmail, "password", "abc12345"), loginRequest);

        assertThat(result.getCode()).isEqualTo(200);
        verify(userService).login(longEmail, "abc12345", "203.0.113.7");
    }

    /** 超过最宽列（users.email VARCHAR(100)）的标识不可能命中任何行，按"账号"而非"用户名"报错 */
    @Test
    void login_rejectsIdentifierLongerThanWidestColumn() {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        loginRequest.setRemoteAddr("203.0.113.7");

        ApiResponse<Map<String, String>> result = authController.login(
                Map.of("username", "a".repeat(101), "password", "abc12345"), loginRequest);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("账号").doesNotContain("用户名");
        verify(userService, never()).login(anyString(), anyString(), anyString());
    }

    /** 同一个输入框现在接受三态标识，提示文案不能再自称"用户名"（与 UserService 的"账号和密码不能为空"同口径） */
    @Test
    void login_withoutIdentifier_saysAccountNotUsername() {
        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        loginRequest.setRemoteAddr("203.0.113.7");

        ApiResponse<Map<String, String>> result = authController.login(
                Map.of("password", "abc12345"), loginRequest);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("账号不能为空");
    }
}
