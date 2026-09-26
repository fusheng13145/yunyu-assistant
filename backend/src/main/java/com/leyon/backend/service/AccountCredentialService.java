package com.leyon.backend.service;

import com.leyon.backend.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号凭据版本（v2.42 · C-93）
 * <p>
 * 解决的事实：签名正确就永久有效，而"作废一个账号的凭据"此前没有任何落点——
 * 改密不触碰令牌，access 又长达数十小时，被盗令牌可以一直用（refresh 还不验密码，
 * 于是连"改密踢下线"这条直觉都不成立）。本服务给出唯一的判据：
 * <b>令牌里携带的版本号必须等于账号当前的版本号</b>。
 * <p>
 * 只依赖 {@link UserMapper}：{@code UserService} 已经注入 {@code JwtUtil}，
 * 若这里反过来依赖 {@code UserService}，凭据校验就成了循环依赖。
 * 每次校验一次主键点查，不做进程内缓存——缓存会重新打开"改密后 N 秒仍可用"的窗口，
 * 而那正是本批要关的东西。
 *
 * @author leyon
 */
@Service
public class AccountCredentialService {

    private static final Logger logger = LoggerFactory.getLogger(AccountCredentialService.class);

    /** v2.42 之前签发的存量令牌没有 tv claim，按初始版本处理（升级不踢人） */
    public static final int LEGACY_TOKEN_VERSION = 0;

    private final UserMapper userMapper;

    public AccountCredentialService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 取账号当前凭据版本，供签发时写入令牌
     *
     * @param userId 用户ID
     * @return 版本号
     * @throws IllegalStateException 账号不存在或已注销——此时出票等于凭空造凭据
     */
    public int currentVersion(String userId) {
        Integer version = selectVersion(userId);
        if (version == null) {
            throw new IllegalStateException("账号不存在或已注销，无法为其签发凭据");
        }
        return version;
    }

    /**
     * 令牌凭据是否仍然有效：账号存在（含未被逻辑删除）且版本一致
     *
     * @param userId       令牌主体
     * @param tokenVersion 令牌携带的版本，缺失按 {@link #LEGACY_TOKEN_VERSION}
     * @return true-仍有效 false-账号已消失或凭据已被作废
     */
    public boolean isCredentialLive(String userId, int tokenVersion) {
        Integer current = selectVersion(userId);
        if (current == null) {
            return false;
        }
        if (current != tokenVersion) {
            logger.warn("凭据版本不符，令牌已作废：userId={}，令牌版本={}，当前版本={}",
                    userId, tokenVersion, current);
            return false;
        }
        return true;
    }

    /**
     * 按主键只取版本列。SQL 里显式带了未删除条件（与 {@code @TableLogic} 同口径），
     * 所以"查不到"同时覆盖了不存在与已注销两种情况。
     *
     * @return 版本号；账号不存在或已注销返回 {@code null}
     */
    private Integer selectVersion(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userMapper.selectTokenVersion(userId);
    }
}
