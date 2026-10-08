package com.leyon.backend.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TURN REST 凭据签发判据：算法对得上 coturn（已知答案向量由仓外独立实现算出）、
 * 未配置与半途配置一律不签发（不发假凭据）、TTL 有界且到期时刻可断言。
 *
 * @author leyon
 */
class TurnCredentialServiceTest {

    private static final String SECRET = "test-secret";
    private static final String REALM = "turn.example.com";

    private TurnCredentialService service(String secret, String realm, long ttl) {
        return new TurnCredentialService(secret, realm, ttl);
    }

    /** 与实现无关的参照：python hmac.new(secret, username, sha1) → base64（到期时刻 1700000000 由 now+ttl 合成） */
    @Test
    void issue_matchesKnownAnswerVector() {
        Optional<TurnCredentialService.Offer> offer =
                service(SECRET, REALM, 3600).issue("user_admin", 1699996400L);
        assertThat(offer).isPresent();
        assertThat(offer.get().username()).isEqualTo("1700000000:user_admin");
        assertThat(offer.get().credential()).isEqualTo("HW3OlpYEYUx6Nz7uWXkcskuYjK0=");
        assertThat(offer.get().expiresAt()).isEqualTo(1700000000L);
    }

    @Test
    void issue_urlsCarryRealmAndBothTransports() {
        TurnCredentialService.Offer offer = service(SECRET, REALM, 3600).issue("u_1", 0L).orElseThrow();
        assertThat(offer.username()).isEqualTo("3600:u_1");
        assertThat(offer.urls()).containsExactly(
                "turn:turn.example.com:3478?transport=udp",
                "turn:turn.example.com:3478?transport=tcp");
        assertThat(offer.credential()).isEqualTo("cY5vZXhuiNpGHuYmv3kEPEDMb5c=");
    }

    @Test
    void issue_sanitizesIdentitySoCoturnSplitsOnFirstColonOnly() {
        TurnCredentialService.Offer offer = service(SECRET, REALM, 600).issue("or:ga n/id", 1000L).orElseThrow();
        assertThat(offer.username()).isEqualTo("1600:or_ga_n_id");
        assertThat(offer.username().split(":")).hasSize(2);
        assertThat(offer.credential()).isEqualTo("hd5RCAijIPSj/57XDeoQ5k6UrQI=");
    }

    @Test
    void notConfiguredOrHalfConfigured_issuesNothing() {
        assertThat(service("", REALM, 3600).issue("u", 0L)).isEmpty();
        assertThat(service(SECRET, "", 3600).issue("u", 0L)).isEmpty();
        assertThat(service("", "", 3600).isConfigured()).isFalse();
    }

    /** 反向锚点：没有调用者身份就不签——否则匿名凭据可被任何拿到响应体的人复用 */
    @Test
    void blankIdentity_issuesNothing() {
        assertThat(service(SECRET, REALM, 3600).issue(null, 0L)).isEmpty();
        assertThat(service(SECRET, REALM, 3600).issue("  ", 0L)).isEmpty();
    }

    @Test
    void ttlIsClampedToBounds() {
        assertThat(service(SECRET, REALM, 1).issue("u", 0L).orElseThrow().expiresAt()).isEqualTo(TurnCredentialService.MIN_TTL_SEC);
        assertThat(service(SECRET, REALM, 999_999).issue("u", 0L).orElseThrow().expiresAt()).isEqualTo(TurnCredentialService.MAX_TTL_SEC);
        assertThat(new TurnCredentialService(SECRET, REALM, 0).getTtlSec()).isEqualTo(TurnCredentialService.MIN_TTL_SEC);
    }
}
