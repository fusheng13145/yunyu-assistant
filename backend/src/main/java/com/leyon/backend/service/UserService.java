package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import com.leyon.backend.util.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final InviteCodeService inviteCodeService;
    private final IdentifierPolicy identifierPolicy;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, JwtUtil jwtUtil, LoginAttemptService loginAttemptService,
                       InviteCodeService inviteCodeService, IdentifierPolicy identifierPolicy) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.loginAttemptService = loginAttemptService;
        this.inviteCodeService = inviteCodeService;
        this.identifierPolicy = identifierPolicy;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 用户注册（v2.89 起可带邮箱/手机号；v2.37 起受邀请码闸门约束）
     * <p>
     * 领取与建号在同一事务内：账号插入失败（如唯一索引竞态）时事务回滚，被领取的码随之释放，
     * 不会出现"码烧掉、号没建出来"。<b>形状校验、格式校验与三项查重全部排在领取之前</b>，
     * 顺序错一次的代价正是那一个已经用掉的码。
     * <p>
     * 邮箱/手机号按归一化值查重并落库；用户名按原样落库（存量行的存法如此，读侧也不归一，
     * 见 {@link IdentifierPolicy}）。空串归成 NULL——迁移 0013 的唯一索引只让活行参与，
     * NULL 不冲突而空串冲突，写成空串会让第二个"没填邮箱"的人注册失败。
     *
     * @param username   用户名，不得是邮箱/手机号形状
     * @param password   明文密码
     * @param email      邮箱，可选
     * @param phone      手机号，可选
     * @param inviteCode 邀请码；{@code app.registration.mode=invite} 时必填且一次性
     * @return 注册成功的用户信息（即落库的那一行，含口令哈希；外发抑制由 {@link User} 实体负责）
     * @throws RuntimeException       用户名已存在 / 邮箱或手机号已被注册 / 缺码 / 邀请码无效或已被使用
     * @throws IllegalArgumentException 用户名形状、邮箱格式或手机号格式不合法
     */
    @Transactional
    public User register(String username, String password, String email, String phone, String inviteCode) {
        // 基础入参校验
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new RuntimeException("用户名和密码不能为空");
        }
        // 写侧不变量：用户名不得占用邮箱/手机号形状，否则登录时同一串标识符可能命中两列
        identifierPolicy.validateUsernameShape(username);

        String normalizedEmail = identifierPolicy.normalizeEmail(email);
        if (normalizedEmail != null) {
            identifierPolicy.validateEmail(normalizedEmail);
            if (!userMapper.selectActiveByEmail(normalizedEmail).isEmpty()) {
                throw new RuntimeException("该邮箱已被注册");
            }
        }
        String normalizedPhone = identifierPolicy.normalizePhone(phone);
        if (normalizedPhone != null) {
            identifierPolicy.validatePhone(normalizedPhone);
            if (!userMapper.selectActiveByPhone(normalizedPhone).isEmpty()) {
                throw new RuntimeException("该手机号已被注册");
            }
        }

        // 校验用户名是否重复
        if (!userMapper.selectActiveByUsername(username).isEmpty()) {
            throw new RuntimeException("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(normalizedEmail);
        user.setPhone(normalizedPhone);
        // 密码加密存储
        user.setPassword(passwordEncoder.encode(password));
        // 新注册用户默认为普通用户
        user.setRole(User.ROLE_USER);

        if (inviteCodeService.inviteRequired()) {
            if (!StringUtils.hasText(inviteCode)) {
                throw new RuntimeException("当前为邀请码注册模式，请填写邀请码");
            }
            // 先定 id 再领取：invite_code.used_by 记的就是这次建出来的账号，两步必须指向同一主体
            String newUserId = UUID.randomUUID().toString().replace("-", "");
            if (!inviteCodeService.claim(inviteCode, newUserId)) {
                throw new RuntimeException("邀请码无效或已被使用");
            }
            user.setId(newUserId);
        }
        userMapper.insert(user);

        return user;
    }

    /**
     * 用户登录（v2.89 起标识可为用户名 / 邮箱 / 手机号）
     * <p>
     * v2.44 起锁定按「来源 + 账号」两个维度判定，故必须拿到可信客户端地址
     * （由 {@code ClientIpResolver} 产出，见 {@code AuthController}）。
     * v2.89 起账号维度的键是<b>归一化后的标识</b>而不是原始输入：按原始串记键时，
     * 交替 "Alice@X.com" / "alice@x.com" 就能把 15 次阈值摊薄到一半以下。
     *
     * @param identifier 用户名、邮箱或手机号（形状判据见 {@link IdentifierPolicy#classify}）
     * @param password   明文密码
     * @param clientIp   可信客户端地址；为空时只按账号维度判定（不把"取不到地址"当成"来自某个共享桶"）
     * @return 登录结果：token、refreshToken、用户ID、用户名、昵称、头像
     * @throws RuntimeException 账号或密码错误 / 标识对应多个账号 / 登录被临时锁定
     */
    public Map<String, String> login(String identifier, String password, String clientIp) {
        if (!StringUtils.hasText(identifier) || !StringUtils.hasText(password)) {
            throw new RuntimeException("账号和密码不能为空");
        }

        IdentifierPolicy.Kind kind = identifierPolicy.classify(identifier);
        LoginAttemptService.LoginLockTarget usernameTarget =
                LoginAttemptService.LoginLockTarget.username(lockKeyFor(kind, identifier));
        LoginAttemptService.LoginLockTarget ipTarget = StringUtils.hasText(clientIp)
                ? LoginAttemptService.LoginLockTarget.ip(clientIp)
                : null;

        // 锁定检查：来源维度先判（它是"这台机器在乱试"），账号维度后判（"这个号被人盯上了"）
        long remainingLockMs = ipTarget == null ? 0L : loginAttemptService.getRemainingLockMs(ipTarget);
        if (remainingLockMs <= 0) {
            remainingLockMs = loginAttemptService.getRemainingLockMs(usernameTarget);
        }
        if (remainingLockMs > 0) {
            throw new RuntimeException("登录失败次数过多，已临时锁定，请 "
                    + (remainingLockMs / 1000 / 60 + 1) + " 分钟后再试");
        }

        List<User> rows = lookupByKind(kind, identifier);
        if (rows.isEmpty() && kind != IdentifierPolicy.Kind.USERNAME) {
            // 形状判据只是读侧启发式，不是存量约束：库里叫 13800000000 的用户名不能因为"像手机号"就登不进来
            rows = userMapper.selectActiveByUsername(identifier);
        }
        if (rows.size() > 1) {
            // 多命中一律拒绝：LIMIT 1 挑一行等于让同标识的每行都能凭自己的口令登录，而日志里一切正常。
            // 只在迁移 0013 未跑到（无硬唯一）的实例上可达，故此文案刻意点名的不是口令而是数据状态。
            throw new RuntimeException("该标识对应多个账号，请改用用户名登录或联系管理员");
        }
        User user = rows.isEmpty() ? null : rows.get(0);

        // 密码比对（标识不存在与密码错误返回同一文案，避免账号枚举）
        boolean passwordOk = user != null && passwordEncoder.matches(password, user.getPassword());
        if (!passwordOk) {
            loginAttemptService.recordFailure(usernameTarget);
            if (ipTarget != null) {
                loginAttemptService.recordFailure(ipTarget);
            }
            throw new RuntimeException("账号或密码错误");
        }

        // 登录成功：两个维度的失败计数一起清除
        if (ipTarget == null) {
            loginAttemptService.recordSuccess(usernameTarget);
        } else {
            loginAttemptService.recordSuccess(usernameTarget, ipTarget);
        }

        // 生成访问令牌 + 刷新令牌
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());
        Map<String, String> result = new HashMap<>();
        result.put("token", token);
        result.put("refreshToken", refreshToken);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole() == null ? User.ROLE_USER : user.getRole());

        if (StringUtils.hasText(user.getNickname())) {
            result.put("nickname", user.getNickname());
        }
        if (StringUtils.hasText(user.getAvatar())) {
            result.put("avatar", user.getAvatar());
        }
        return result;
    }

    /**
     * 按形状选出查询列（邮箱/手机号入参先归一化，用户名按原样查——不对称的来由见 {@link IdentifierPolicy}）
     */
    private List<User> lookupByKind(IdentifierPolicy.Kind kind, String identifier) {
        return switch (kind) {
            case EMAIL -> userMapper.selectActiveByEmail(identifierPolicy.normalizeEmail(identifier));
            case PHONE -> userMapper.selectActiveByPhone(identifierPolicy.normalizePhone(identifier));
            case USERNAME -> userMapper.selectActiveByUsername(identifier);
        };
    }

    /**
     * 账号维度锁定用的键：邮箱/手机号取归一化形式，用户名取原样。
     * <p>
     * 兜底路径（形状判据落到用户名）也用它，判定"同一个人在重试"看的是他打的这串凭据，
     * 不是这串凭据最终命中了哪一列。
     */
    private String lockKeyFor(IdentifierPolicy.Kind kind, String identifier) {
        return switch (kind) {
            case EMAIL -> identifierPolicy.normalizeEmail(identifier);
            case PHONE -> identifierPolicy.normalizePhone(identifier);
            case USERNAME -> identifier;
        };
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

        // 更新新密码，并在同一条语句里推进凭据版本：
        // 分两步写会留"密码已改、旧令牌仍有效"的窗口，也可能只成功一半
        return userMapper.updatePasswordAndBumpTokenVersion(userId, passwordEncoder.encode(newPassword)) > 0;
    }
}