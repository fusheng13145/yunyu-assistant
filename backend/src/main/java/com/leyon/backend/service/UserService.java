package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.util.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户业务服务
 * 提供用户注册、登录、信息查询、密码修改等功能
 *
 * @author leyon
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final LoginAttemptService loginAttemptService;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, JwtUtil jwtUtil, LoginAttemptService loginAttemptService) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 用户注册
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 注册成功的用户信息（已清空密码字段）
     * @throws RuntimeException 用户名已存在时抛出异常
     */
    public User register(String username, String password) {
        // 基础入参校验
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new RuntimeException("用户名和密码不能为空");
        }

        // 校验用户名是否重复
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username);
        Long count = userMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        // 密码加密存储
        user.setPassword(passwordEncoder.encode(password));
        userMapper.insert(user);

        // 响应脱敏，清空密码
        user.setPassword(null);
        return user;
    }

    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 登录结果：token、refreshToken、用户ID、用户名、昵称、头像
     * @throws RuntimeException 用户名或密码错误 / 账号临时锁定
     */
    public Map<String, String> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new RuntimeException("用户名和密码不能为空");
        }

        // 登录失败锁定检查：锁定期间直接拒绝
        long remainingLockMs = loginAttemptService.getRemainingLockMs(username);
        if (remainingLockMs > 0) {
            throw new RuntimeException("登录失败次数过多，账号已临时锁定，请 "
                    + (remainingLockMs / 1000 / 60 + 1) + " 分钟后再试");
        }

        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username);
        User user = userMapper.selectOne(queryWrapper);

        // 密码比对（用户不存在与密码错误返回同一文案，避免用户名枚举）
        boolean passwordOk = user != null && passwordEncoder.matches(password, user.getPassword());
        if (!passwordOk) {
            loginAttemptService.recordFailure(username);
            throw new RuntimeException("用户名或密码错误");
        }

        // 登录成功：清除失败计数
        loginAttemptService.recordSuccess(username);

        // 生成访问令牌 + 刷新令牌
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());
        Map<String, String> result = new HashMap<>();
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());

        if (StringUtils.hasText(user.getNickname())) {
            result.put("nickname", user.getNickname());
        }
        if (StringUtils.hasText(user.getAvatar())) {
            result.put("avatar", user.getAvatar());
        }
        return result;
    }

    /**
     * 根据用户ID查询用户信息
     *
     * @param id 用户ID
     * @return 用户实体，不存在返回 null
     */
    public User getById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return userMapper.selectById(id);
    }

    /**
     * 根据用户名查询用户信息
     *
     * @param username 用户名
     * @return 用户实体，不存在返回 null
     */
    public User getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username);
        return userMapper.selectOne(queryWrapper);
    }

    /**
     * 修改用户密码
     *
     * @param userId      用户ID
     * @param oldPassword 原明文密码
     * @param newPassword 新明文密码
     * @return true-修改成功 false-原密码错误
     * @throws RuntimeException 用户不存在时抛出异常
     */
    public boolean changePassword(String userId, String oldPassword, String newPassword) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(oldPassword) || !StringUtils.hasText(newPassword)) {
            throw new RuntimeException("参数不能为空");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 校验原密码
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return false;
        }

        // 更新新密码
        user.setPassword(passwordEncoder.encode(newPassword));
        return userMapper.updateById(user) > 0;
    }
}