package com.leyon.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号凭据版本判据单测（v2.42 · C-93）
 * <p>
 * 用内存假账号表（{@link FakeUserMapper}）而不是 Mockito：判据的实质是"行在不在、版本对不对、
 * NULL 列怎么读"，这三条都能用一张 Map 建模出来；桩返回值的形状由假表决定，而不是由打桩语句决定，
 * 才能真的证明判定逻辑（SQL 字面语义另见真机一轮，手册 7.4 v2.42）。
 *
 * @author leyon
 */
class AccountCredentialServiceTest {

    private AccountCredentialService service(FakeUserMapper mapper) {
        return new AccountCredentialService(mapper);
    }

    @Test
    @DisplayName("版本一致 ⇒ 凭据有效")
    void matchingVersionIsLive() {
        AccountCredentialService service = service(new FakeUserMapper().with("u-1", 2));
        assertThat(service.isCredentialLive("u-1", 2)).isTrue();
    }

    @Test
    @DisplayName("版本落后一格（刚改过密）⇒ 立即失效，不等令牌自然过期")
    void staleVersionIsDead() {
        AccountCredentialService service = service(new FakeUserMapper().with("u-1", 3));

        assertThat(service.isCredentialLive("u-1", 2)).isFalse();
        assertThat(service.isCredentialLive("u-1", 4)).isFalse();
    }

    @Test
    @DisplayName("账号查不到（不存在或已逻辑删除）⇒ 一律失效，与版本无关")
    void missingAccountIsDead() {
        AccountCredentialService service = service(new FakeUserMapper());

        assertThat(service.isCredentialLive("u-gone", 0)).isFalse();
        assertThat(service.isCredentialLive("u-gone", 7)).isFalse();
    }

    @Test
    @DisplayName("列值为 NULL 的旧行按初始版本 0 读，不把整表判成失效")
    void nullColumnReadsAsLegacyVersion() {
        AccountCredentialService service = service(new FakeUserMapper().with("u-old", null));

        assertThat(service.currentVersion("u-old")).isEqualTo(AccountCredentialService.LEGACY_TOKEN_VERSION);
        assertThat(service.isCredentialLive("u-old", AccountCredentialService.LEGACY_TOKEN_VERSION)).isTrue();
    }

    @Test
    @DisplayName("给不存在的账号出票是异常，不是静默签一张永远无效的令牌")
    void issuingForMissingAccountThrows() {
        AccountCredentialService service = service(new FakeUserMapper());

        assertThatThrownBy(() -> service.currentVersion("u-gone"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("空 userId 不查库直接判失效")
    void blankUserIdIsDeadWithoutQuery() {
        // 假表里放一个版本 0 的账号：若空 userId 被误当查询条件，可能意外命中
        AccountCredentialService service = service(new FakeUserMapper().with("u-1", 0));

        assertThat(service.isCredentialLive(null, 0)).isFalse();
        assertThat(service.isCredentialLive("  ", 0)).isFalse();
    }

    @Test
    @DisplayName("改密那条语句推进版本后，同一账号的旧版本判据立刻翻转")
    void bumpFlipsTheVerdict() {
        FakeUserMapper mapper = new FakeUserMapper().with("u-1", 0);
        AccountCredentialService service = service(mapper);
        assertThat(service.isCredentialLive("u-1", 0)).isTrue();

        assertThat(mapper.updatePasswordAndBumpTokenVersion("u-1", "hash")).isEqualTo(1);

        assertThat(service.isCredentialLive("u-1", 0)).isFalse();
        assertThat(service.isCredentialLive("u-1", 1)).isTrue();
    }
}
