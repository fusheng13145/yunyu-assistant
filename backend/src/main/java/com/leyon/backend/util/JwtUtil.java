package com.leyon.backend.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 * 负责令牌生成、解析、校验
 *
 * @author leyon
 */
@Component
public class JwtUtil {

    /**
     * JWT 加密密钥
     */
    @Value("${app.jwt.secret}")
    private String secret;

    /**
     * JWT 过期时长(毫秒)
     */
    @Value("${app.jwt.expiration}")
    private long expiration;

    /**
     * 获取签名密钥
     *
     * @return 加密密钥
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT 令牌
     *
     * @param userId   用户ID
     * @param username 用户名
     * @return JWT 字符串
     */
    public String generateToken(String userId, String username) {
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
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
        return claims.get("username", String.class);
    }

    /**
     * 校验令牌有效性
     * 捕获格式错误、签名错误、过期等异常
     *
     * @param token JWT令牌
     * @return true-有效 false-无效
     */
    public boolean validateToken(String token) {
        try {
            getClaimsByToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}