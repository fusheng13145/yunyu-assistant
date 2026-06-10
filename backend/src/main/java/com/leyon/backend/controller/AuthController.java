package com.leyon.backend.controller;

import com.leyon.backend.entity.User;
import com.leyon.backend.model.ApiResponse;
import com.leyon.backend.service.UserService;
import com.leyon.backend.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
        String name = body.get("name");
        String password = body.get("password");
        User user = userService.register(name, password);
        String token = jwtUtil.generateToken(user.getId(), user.getName());
        Map<String, String> result = Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getName()
        );
        return ApiResponse.success(result);
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String password = body.get("password");
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
}
