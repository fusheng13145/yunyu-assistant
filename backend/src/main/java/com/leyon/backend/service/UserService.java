package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.util.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 用户注册
     */
    public User register(String username, String password) {
        // 检查用户名是否已存在
        Long count = userMapper.selectCount(
            new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        userMapper.insert(user);

        // 返回时不包含密码
        user.setPassword(null);
        return user;
    }

    /**
     * 用户登录
     */
    public Map<String, String> login(String username, String password) {
        User user = userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        Map<String, String> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        if (user.getNickname() != null) {
            result.put("nickname", user.getNickname());
        }
        if (user.getAvatar() != null) {
            result.put("avatar", user.getAvatar());
        }
        return result;
    }

    /**
     * 根据ID获取用户
     */
    public User getById(String id) {
        return userMapper.selectById(id);
    }

    /**
     * 根据用户名获取用户
     */
    public User getByUsername(String username) {
        return userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
    }

    /**
     * 修改密码
     */
    public boolean changePassword(String userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return false;
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        return userMapper.updateById(user) > 0;
    }
}
