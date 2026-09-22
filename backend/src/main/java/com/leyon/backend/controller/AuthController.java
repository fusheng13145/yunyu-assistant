package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.User;
import com.leyon.backend.service.TokenBlacklistService;
import com.leyon.backend.service.UserService;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
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

    /** 密码复杂度正则：至少包含大小写字母、数字中的两种，长度 6-128 */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].{5,}$"
    );
    /** 宽松模式密码正则：生产环境建议启用上面的严格模式 */
    private static final Pattern PASSWORD_PATTERN_RELAXED = Pattern.compile(
            "^(?=.*[a-zA-Z])(?=.*\\d).{5,}$"
    );

    public AuthController(UserService userService, JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 用户注册
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
        // 密码复杂度校验：至少包含字母和数字
        if (!PASSWORD_PATTERN_RELAXED.matcher(password).matches()) {
            return ApiResponse.paramError("密码必须至少包含一个字母和一个数字，长度不少于6位");
        }

        User user = userService.register(username, password);
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

        Map<String, String> result = userService.login(username, password);
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
        String username = jwtUtil.getUsernameFromToken(refreshToken);

        // 旧 refresh token 作废（轮换）
        String oldJti = jwtUtil.getJtiFromToken(refreshToken);
        if (oldJti != null) {
            tokenBlacklistService.blacklist(oldJti, 7 * 24 * 3600_000L);
        }

        Map<String, String> result = new java.util.HashMap<>();
        result.put("token", jwtUtil.generateToken(userId, username));
        result.put("refreshToken", jwtUtil.generateRefreshToken(userId, username));
        result.put("userId", userId);
        result.put("username", username);
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
            if (jwtUtil.validateToken(accessToken)) {
                String jti = jwtUtil.getJtiFromToken(accessToken);
                if (jti != null) {
                    tokenBlacklistService.blacklist(jti, 7 * 24 * 3600_000L);
                }
            }
        }
        // 2. 刷新令牌（请求体，可选）
        if (body != null) {
            String refreshToken = body.get("refreshToken");
            if (StringUtils.hasText(refreshToken) && jwtUtil.isTokenType(refreshToken, JwtUtil.TOKEN_TYPE_REFRESH)) {
                String jti = jwtUtil.getJtiFromToken(refreshToken);
                if (jti != null) {
                    tokenBlacklistService.blacklist(jti, 7 * 24 * 3600_000L);
                }
            }
        }
        return ApiResponse.success();
    }

    /**
     * 获取当前登录用户信息
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
        // 密码脱敏
        user.setPassword(null);
        return ApiResponse.success(user);
    }

    /**
     * 从 Authorization 头解析用户ID（供放行路径使用）
     */
    private String resolveUserIdFromHeader(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
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
        if (!PASSWORD_PATTERN_RELAXED.matcher(newPassword).matches()) {
            return ApiResponse.paramError("新密码必须至少包含一个字母和一个数字，长度不少于6位");
        }

        boolean success = userService.changePassword(userId, oldPassword, newPassword);
        if (success) {
            return ApiResponse.success();
        }
        return ApiResponse.error("原密码不正确");
    }
}