package com.leyon.backend.controller;

import com.leyon.backend.annotation.Audit;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.User;
import com.leyon.backend.service.UserService;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 账号认证接口
 * 包含注册、登录、获取个人信息、修改密码功能
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    /** 密码复杂度正则：至少包含大小写字母、数字中的两种，长度 6-128 */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].{5,}$"
    );
    /** 宽松模式密码正则：生产环境建议启用上面的严格模式 */
    private static final Pattern PASSWORD_PATTERN_RELAXED = Pattern.compile(
            "^(?=.*[a-zA-Z])(?=.*\\d).{5,}$"
    );

    public AuthController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
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
        Map<String, String> result = Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername()
        );
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
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ApiResponse<User> me(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
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
     * 修改登录密码
     */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
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