package com.leyon.backend.controller;

import com.leyon.backend.entity.User;
import com.leyon.backend.model.ApiResponse;
import com.leyon.backend.service.UserService;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public AuthController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, String>> register(@RequestBody Map<String, String> body) {
        // 校验参数
        // 向后兼容：同时支持 name 和 username 参数名
        String name = body.get("username");
        if (name == null || name.isBlank()) {
            name = body.get("name");
        }
        String password = body.get("password");
        if (name == null || name.isBlank()) {
            return ApiResponse.paramError("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            return ApiResponse.paramError("密码不能为空");
        }
        if (name.length() > 32) {
            return ApiResponse.paramError("用户名长度不能超过32个字符");
        }
        if (password.length() > 128) {
            return ApiResponse.paramError("密码长度不能超过128个字符");
        }
        User user = userService.register(name, password);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        Map<String, String> result = Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername()
        );
        return ApiResponse.success(result);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestBody Map<String, String> body) {
        // 校验参数
        // 向后兼容：同时支持 name 和 username 参数名
        String name = body.get("username");
        if (name == null || name.isBlank()) {
            name = body.get("name");
        }
        String password = body.get("password");
        if (name == null || name.isBlank()) {
            return ApiResponse.paramError("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            return ApiResponse.paramError("密码不能为空");
        }
        if (name.length() > 32) {
            return ApiResponse.paramError("用户名长度不能超过32个字符");
        }
        if (password.length() > 128) {
            return ApiResponse.paramError("密码长度不能超过128个字符");
        }
        Map<String, String> result = userService.login(name, password);
        return ApiResponse.success(result);
    }

    @GetMapping("/me")
    public ApiResponse<User> me(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        User user = userService.getById(userId);
        if (user == null) {
            return ApiResponse.paramError("User not found");
        }
        user.setPassword(null);
        return ApiResponse.success(user);
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        String oldPassword = body.getOrDefault("oldPassword", "");
        String newPassword = body.getOrDefault("newPassword", "");

        // 参数校验
        if (oldPassword == null || oldPassword.isBlank()) {
            return ApiResponse.paramError("请输入原密码");
        }
        if (newPassword == null || newPassword.isBlank()) {
            return ApiResponse.paramError("请输入新密码");
        }
        if (newPassword.length() < 6) {
            return ApiResponse.paramError("新密码长度不能少于6位");
        }
        if (newPassword.length() > 128) {
            return ApiResponse.paramError("新密码长度不能超过128位");
        }

        boolean success = userService.changePassword(userId, oldPassword, newPassword);
        if (success) {
            return ApiResponse.success(null);
        } else {
            return ApiResponse.error("原密码不正确");
        }
    }
}
