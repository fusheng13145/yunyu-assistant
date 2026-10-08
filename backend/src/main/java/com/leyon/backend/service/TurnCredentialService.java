package com.leyon.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * TURN REST API 凭据签发（coturn `use-auth-secret` 同算法：
 * username = {@code <到期 Unix 秒>:<调用者标识>}，credential = base64(HMAC-SHA1(共享密钥, username))）。
 *
 * @author leyon
 */
@Service
public class TurnCredentialService {

    private static final Logger log = LoggerFactory.getLogger(TurnCredentialService.class);

    /** 下限：短于一次建链重试的窗口就没有意义；上限：一天，过期不续的凭据等于变相长期凭据 */
    public static final long MIN_TTL_SEC = 300;
    public static final long MAX_TTL_SEC = 24 * 60 * 60;

    private final String secret;
    private final String realm;
    private final long ttlSec;

    public TurnCredentialService(@Value("${app.webrtc.turn.secret:}") String secret,
                                 @Value("${app.webrtc.turn.realm:}") String realm,
                                 @Value("${app.webrtc.turn.ttl-sec:7200}") long ttlSec) {
        this.secret = secret == null ? "" : secret.trim();
        this.realm = realm == null ? "" : realm.trim();
        this.ttlSec = Math.min(Math.max(ttlSec, MIN_TTL_SEC), MAX_TTL_SEC);
    }

    /** 半途配置（只给密钥或只给 realm）不签发，但必须说出来——静默不签与"没打算配"同形 */
    @PostConstruct
    void warnHalfConfigured() {
        boolean hasSecret = StringUtils.hasText(secret);
        boolean hasRealm = StringUtils.hasText(realm);
        if (hasSecret != hasRealm) {
            log.warn("TURN 凭据签发未启用：app.webrtc.turn.secret 与 app.webrtc.turn.realm 只配置了其中一个（缺 {}）",
                    hasSecret ? "realm" : "secret");
        }
    }

    public boolean isConfigured() {
        return StringUtils.hasText(secret) && StringUtils.hasText(realm);
    }

    public long getTtlSec() {
        return ttlSec;
    }

    public String getRealm() {
        return realm;
    }

    public record Offer(String username, String credential, long expiresAt, List<String> urls) {
    }

    /**
     * 为一次建链现签凭据。未配置或缺调用者标识时返回空（不发假凭据，前端照旧回退 STUN）。
     *
     * @param nowEpochSec 由调用方注入，便于测试断言到期时刻
     */
    public Optional<Offer> issue(String userId, long nowEpochSec) {
        if (!isConfigured() || !StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        long expiresAt = nowEpochSec + ttlSec;
        String username = expiresAt + ":" + sanitize(userId);
        String credential = sign(username);
        if (credential.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Offer(username, credential, expiresAt,
                List.of("turn:" + realm + ":3478?transport=udp", "turn:" + realm + ":3478?transport=tcp")));
    }

    /** coturn 按第一个冒号切分到期时刻，标识里的冒号与空白会让它切错 */
    private static String sanitize(String userId) {
        return userId.replaceAll("[^A-Za-z0-9_.@-]", "_");
    }

    private String sign(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 签发失败不能下发半成品凭据；浏览器拿不到 TURN 会回退 STUN，比"配了但永远 401"好诊断
            log.warn("TURN 凭据签发失败: {}", e.toString());
            return "";
        }
    }
}
