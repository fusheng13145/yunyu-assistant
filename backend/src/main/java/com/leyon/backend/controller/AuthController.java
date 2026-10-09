package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.User;
import com.leyon.backend.service.InviteCodeService;
import com.leyon.backend.service.TokenBlacklistService;
import com.leyon.backend.service.UserService;
import com.leyon.backend.util.ClientIpResolver;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 账号认证接口
 * 包含注册、登录、刷新令牌、登出、获取个人信息、修改密码功能
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * 黑名单条目在令牌剩余有效期之上追加的保险余量（毫秒，JWT_BLACKLIST_TTL_MARGIN_MS，默认 60 秒）：
     * 覆盖校验与写库之间极小的时钟偏移。C-95 收口后黑名单存活 = 令牌剩余期 + 余量，不再写死 7 天。
     */
    @Value("${app.jwt.blacklist-ttl-margin-ms:60000}")
    private long blacklistTtlMarginMs;
    private final InviteCodeService inviteCodeService;
    private final ClientIpResolver clientIpResolver;

    /** 密码规则：长度 6-128 且至少含一个字母与一个数字（与前端注册校验、报错文案一致） */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-zA-Z])(?=.*\\d).{6,}$"
    );

    /**
     * 登录标识的长度上限取三态里最宽的那一列（{@code users.email VARCHAR(100)}），而不是用户名的 32：
     * 上限比列窄会把合法邮箱拦在控制器里并报"用户名太长"，比列宽则只多一次注定落空的查询。
     */
    private static final int MAX_LOGIN_IDENTIFIER_LENGTH = 100;

    public AuthController(UserService userService, JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService,
                          InviteCodeService inviteCodeService, ClientIpResolver clientIpResolver) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
        this.inviteCodeService = inviteCodeService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * 注册前置配置：前端注册页据此决定邀请码输入框与文案
     * 两个字段来自同一次 {@link InviteCodeService} 判定，避免"文案说放开、表单要码"的漂移；
     * 邮箱/手机号是常规注册的可选项、不是档位（没有找回通道，强制填邮箱换不到任何运维收益）
     */
    @GetMapping("/register-config")
    public ApiResponse<Map<String, Object>> registerConfig() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("inviteRequired", inviteCodeService.inviteRequired());
        data.put("mode", inviteCodeService.mode());
        return ApiResponse.success(data);
    }

    /**
     * 用户注册（v2.89 起可带邮箱/手机号，二者皆可选）
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, String>> register(@RequestBody Map<String, String> body) {
        // 兼容 username / name 两种字段
        String username = body.get("username");
        if (!StringUtils.hasText(username)) {
            username = body.get("name");
        }
        String password = body.get("password");

        // 参数校验
        if (!StringUtils.hasText(username)) {
            return ApiResponse.paramError("用户名不能为空");
        }
        if (!StringUtils.hasText(password)) {
            return ApiResponse.paramError("密码不能为空");
        }
        if (username.length() > 32) {
            return ApiResponse.paramError("用户名长度不能超过32个字符");
        }
        if (password.length() > 128) {
            return ApiResponse.paramError("密码长度不能超过128个字符");
        }
        // 密码规则校验：≥6 位且至少包含一个字母和一个数字
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            return ApiResponse.paramError("密码必须至少包含一个字母和一个数字，长度不少于6位");
        }

        User user = userService.register(username, password, body.get("email"), body.get("phone"),
                body.get("inviteCode"));
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());
        Map<String, String> result = new java.util.HashMap<>();
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole() == null ? User.ROLE_USER : user.getRole());
        return ApiResponse.success(result);
    }

    /**
     * 用户登录
     * 请求体字段名仍是 username（v2.89 起其值可为用户名、邮箱或手机号，路由由服务端按形状判定）
     */
    @Audit(action = "LOGIN", targetType = "user")
    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        // 兼容 username / name 两种字段
        String username = body.get("username");
        if (!StringUtils.hasText(username)) {
            username = body.get("name");
        }
        String password = body.get("password");

        // 参数校验（"账号"＝用户名/邮箱/手机号三者之一，判态由 UserService 负责）
        if (!StringUtils.hasText(username)) {
            return ApiResponse.paramError("账号不能为空");
        }
        if (!StringUtils.hasText(password)) {
            return ApiResponse.paramError("密码不能为空");
        }
        if (username.length() > MAX_LOGIN_IDENTIFIER_LENGTH) {
            return ApiResponse.paramError("账号长度不能超过" + MAX_LOGIN_IDENTIFIER_LENGTH + "个字符");
        }
        if (password.length() > 128) {
            return ApiResponse.paramError("密码长度不能超过128个字符");
        }

        // 登录锁定按来源维度判定，地址只能取服务端可证明的那一个（v2.44）
        Map<String, String> result = userService.login(username, password, clientIpResolver.resolve(request));
        return ApiResponse.success(result);
    }

    /**
     * 刷新令牌：用 refresh token 换取新的访问令牌与刷新令牌（轮换）
     * 旧 refresh token 加入黑名单，防重放
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (!StringUtils.hasText(refreshToken)) {
            return ApiResponse.paramError("refreshToken 不能为空");
        }
        // 必须是 refresh 类型且签名/有效期有效
        if (!jwtUtil.isTokenType(refreshToken, JwtUtil.TOKEN_TYPE_REFRESH) || !jwtUtil.validateToken(refreshToken)) {
            return ApiResponse.paramError("刷新令牌无效或已过期，请重新登录");
        }
        String userId = jwtUtil.getUserIdFromToken(refreshToken);

        // 账号必须仍存在：软删除后不再续期，否则已注销账号可凭 7 天 refresh token 无限换发
        User user = userService.getById(userId);
        if (user == null) {
            return ApiResponse.paramError("账号不存在或已注销，请重新登录");
        }
        String username = user.getUsername();

        // 旧 refresh token 作废（轮换）
        String oldJti = jwtUtil.getJtiFromToken(refreshToken);
        if (oldJti != null) {
            tokenBlacklistService.blacklist(oldJti, jwtUtil.getRemainingValidityMs(refreshToken) + blacklistTtlMarginMs);
        }

        Map<String, String> result = new java.util.HashMap<>();
        result.put("token", jwtUtil.generateToken(userId, username));
        result.put("refreshToken", jwtUtil.generateRefreshToken(userId, username));
        result.put("userId", userId);
        result.put("username", username);
        result.put("role", user.getRole() == null ? User.ROLE_USER : user.getRole());
        return ApiResponse.success(result);
    }

    /**
     * 登出：将当前访问令牌与刷新令牌加入黑名单（服务端失效）
     * 无需鉴权拦截器（处于 /api/auth/** 放行路径），从请求头解析当前令牌
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) Map<String, String> body,
                                    HttpServletRequest request) {
        // 1. 当前访问令牌（Authorization 头）
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String accessToken = authHeader.substring(7);
            if (jwtUtil.validateAccessToken(accessToken)) {
                String jti = jwtUtil.getJtiFromToken(accessToken);
                if (jti != null) {
                    tokenBlacklistService.blacklist(jti, jwtUtil.getRemainingValidityMs(accessToken) + blacklistTtlMarginMs);
                }
            }
        }
        // 2. 刷新令牌（请求体，可选）
        if (body != null) {
            String refreshToken = body.get("refreshToken");
            if (StringUtils.hasText(refreshToken) && jwtUtil.isTokenType(refreshToken, JwtUtil.TOKEN_TYPE_REFRESH)) {
                String jti = jwtUtil.getJtiFromToken(refreshToken);
                if (jti != null) {
                    tokenBlacklistService.blacklist(jti, jwtUtil.getRemainingValidityMs(refreshToken) + blacklistTtlMarginMs);
                }
            }
        }
        return ApiResponse.success();
    }

    /**
     * 获取当前登录用户信息
     * <p>
     * 直接返回实体：{@code password} / {@code tokenVersion} 由实体上的注解抑制，出口不再手写脱敏（v2.48 · C-107）
     */
    @GetMapping("/me")
    public ApiResponse<User> me(HttpServletRequest request) {
        // /api/auth/** 为拦截器放行路径，此处自行从 Authorization 头解析当前用户
        String userId = (String) request.getAttribute("userId");
        if (!StringUtils.hasText(userId)) {
            userId = resolveUserIdFromHeader(request);
        }
        if (!StringUtils.hasText(userId)) {
            return ApiResponse.paramError("未登录");
        }
        User user = userService.getById(userId);
        if (user == null) {
            return ApiResponse.paramError("用户不存在");
        }
        return ApiResponse.success(user);
    }

    /**
     * 从 Authorization 头解析用户ID（供放行路径使用）
     * 只接受 access 令牌：refresh 令牌出现在这里说明客户端用错了凭据，不能当登录态用
     */
    private String resolveUserIdFromHeader(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateAccessToken(token)) {
                return jwtUtil.getUserIdFromToken(token);
            }
        }
        return null;
    }

    /**
     * 修改登录密码
     */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        // /api/auth/** 为拦截器放行路径，此处自行从 Authorization 头解析当前用户
        String userId = (String) request.getAttribute("userId");
        if (!StringUtils.hasText(userId)) {
            userId = resolveUserIdFromHeader(request);
        }
        if (!StringUtils.hasText(userId)) {
            return ApiResponse.paramError("未登录");
        }
        String oldPassword = body.getOrDefault("oldPassword", "");
        String newPassword = body.getOrDefault("newPassword", "");

        // 参数校验
        if (!StringUtils.hasText(oldPassword)) {
            return ApiResponse.paramError("请输入原密码");
        }
        if (!StringUtils.hasText(newPassword)) {
            return ApiResponse.paramError("请输入新密码");
        }
        if (newPassword.length() < 6) {
            return ApiResponse.paramError("新密码长度不能少于6位");
        }
        if (newPassword.length() > 128) {
            return ApiResponse.paramError("新密码长度不能超过128位");
        }
        // 与注册一致的复杂度校验：至少包含一个字母和一个数字
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            return ApiResponse.paramError("新密码必须至少包含一个字母和一个数字，长度不少于6位");
        }

        boolean success = userService.changePassword(userId, oldPassword, newPassword);
        if (success) {
            return ApiResponse.success();
        }
        return ApiResponse.error("原密码不正确");
    }
}