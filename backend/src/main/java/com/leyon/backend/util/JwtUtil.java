package com.leyon.backend.util;

import com.leyon.backend.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWT 工具类
 * 负责令牌生成、解析、校验，支持访问令牌与刷新令牌双令牌体系
 * 登出令牌经 jti 黑名单失效（TokenBlacklistService）
 *
 * @author leyon
 */
@Component
public class JwtUtil {

    /** 访问令牌类型标识 */
    public static final String TOKEN_TYPE_ACCESS = "access";
    /** 刷新令牌类型标识 */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /** 令牌类型 claim 名称 */
    private static final String CLAIM_TOKEN_TYPE = "type";
    /** 用户名 claim 名称 */
    private static final String CLAIM_USERNAME = "username";
    /** jti claim 名称（JWK 标准字段） */
    private static final String CLAIM_JTI = "jti";

    /**
     * JWT 加密密钥
     */
    @Value("${app.jwt.secret}")
    private String secret;

    /**
     * JWT 过期时长(毫秒) - 访问令牌
     */
    @Value("${app.jwt.expiration}")
    private long expiration;

    /**
     * 刷新令牌过期时长(毫秒) - 默认 7 天
     */
    @Value("${app.jwt.refresh-expiration:604800000}")
    private long refreshExpiration;

    private final TokenBlacklistService tokenBlacklistService;

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);
    /** HMAC-SHA 密钥最小长度（字节） */
    private static final int MIN_SECRET_LENGTH = 32;

    public JwtUtil(TokenBlacklistService tokenBlacklistService) {
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * 启动时校验 JWT Secret 强度，防止使用弱密钥
     */
    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT Secret 未配置，请设置环境变量 JWT_SECRET");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                "JWT Secret 长度不足 " + MIN_SECRET_LENGTH + " 字节（当前: "
                    + secret.getBytes(StandardCharsets.UTF_8).length + " 字节），"
                    + "请使用强随机密钥。生成命令: openssl rand -base64 32"
            );
        }
        // 检测是否使用了已知弱默认值
        if ("yunyu-assistant-jwt-secret-key-2026-min-32-bytes!".equals(secret)) {
            throw new IllegalStateException(
                "检测到默认/示例 JWT Secret，生产环境必须更换为强随机值"
            );
        }
        logger.info("JWT 密钥校验通过，长度: {} 字节", secret.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 获取签名密钥
     *
     * @return 加密密钥
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成访问令牌（携带 jti 与 type=access）
     *
     * @param userId   用户ID
     * @param username 用户名
     * @return JWT 字符串
     */
    public String generateToken(String userId, String username) {
        return generateToken(userId, username, TOKEN_TYPE_ACCESS, expiration);
    }

    /**
     * 生成刷新令牌（携带 jti 与 type=refresh，有效期独立配置）
     *
     * @param userId   用户ID
     * @param username 用户名
     * @return JWT 字符串
     */
    public String generateRefreshToken(String userId, String username) {
        return generateToken(userId, username, TOKEN_TYPE_REFRESH, refreshExpiration);
    }

    /**
     * 生成指定类型与有效期的令牌
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param type     令牌类型（access / refresh）
     * @param ttlMs    有效期（毫秒）
     * @return JWT 字符串
     */
    private String generateToken(String userId, String username, String type, long ttlMs) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + ttlMs);

        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TOKEN_TYPE, type)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expireDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 统一解析 Token 获取载荷
     *
     * @param token JWT令牌
     * @return 载荷对象
     */
    private Claims getClaimsByToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从令牌中获取用户ID
     *
     * @param token JWT令牌
     * @return 用户ID
     */
    public String getUserIdFromToken(String token) {
        Claims claims = getClaimsByToken(token);
        return claims.getSubject();
    }

    /**
     * 从令牌中获取用户名
     *
     * @param token JWT令牌
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = getClaimsByToken(token);
        return claims.get(CLAIM_USERNAME, String.class);
    }

    /**
     * 从令牌中获取 jti（令牌唯一标识，用于登出黑名单）
     *
     * @param token JWT令牌
     * @return jti，异常时返回 null
     */
    public String getJtiFromToken(String token) {
        try {
            Claims claims = getClaimsByToken(token);
            return claims.getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断令牌类型（access / refresh）
     *
     * @param token JWT令牌
     * @param type  期望类型
     * @return true-类型匹配
     */
    public boolean isTokenType(String token, String type) {
        try {
            Claims claims = getClaimsByToken(token);
            return type.equals(claims.get(CLAIM_TOKEN_TYPE, String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验令牌有效性
     * 捕获格式错误、签名错误、过期、黑名单命中等异常
     *
     * @param token JWT令牌
     * @return true-有效 false-无效
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = getClaimsByToken(token);
            // 登出黑名单校验：jti 命中即视为无效
            if (tokenBlacklistService.isBlacklisted(claims.getId())) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验"可作为会话凭据"的令牌：签名、有效期、黑名单之外还要求 {@code type=access}
     * <p>
     * 只调 {@link #validateToken} 是不够的：refresh 令牌默认 7 天有效、access 只有 24 小时
     * （{@code app.jwt.expiration} / {@code app.jwt.refresh-expiration}），
     * 若它也通行于 {@code /api/**} 与 WebSocket，则泄露 refresh 令牌（XSS、日志、误分享）
     * 等于拿到整个 API 的长期访问权，而不只是"换发新令牌"这一项能力；
     * 而且 access 过期后前端会静默续期，任何按 access 短周期失效的设计都会被悄悄绕过。
     *
     * @param token JWT令牌
     * @return true-是可用的 access 令牌
     */
    public boolean validateAccessToken(String token) {
        try {
            Claims claims = getClaimsByToken(token);
            if (!TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return false;
            }
            return !tokenBlacklistService.isBlacklisted(claims.getId());
        } catch (Exception e) {
            return false;
        }
    }
}