package com.leyon.backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部回调地址 SSRF 校验单元测试
 * 覆盖：公网 IP 字面量放行；回环/私有/链路本地(云元数据)/任意本地/IPv6 回环拒绝；
 *       非 http(s) 协议、带用户信息、主机不可解析拒绝
 * 用例统一使用 IP 字面量（RFC 5737 TEST-NET 段），避免单测依赖 DNS
 *
 * @author leyon
 */
class ExternalUrlValidatorTest {

    @Test
    void acceptsPublicAddress() {
        assertThatCode(() -> ExternalUrlValidator.requirePublicHttpUrl("https://203.0.113.10/hook"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ExternalUrlValidator.requirePublicHttpUrl("http://203.0.113.10:8080/hook?a=1"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLoopbackSiteLocalAndLinkLocal() {
        assertRejected("http://127.0.0.1/hook");
        assertRejected("http://localhost:8080/hook");
        assertRejected("http://[::1]/hook");
        assertRejected("http://0.0.0.0/hook");
        assertRejected("http://10.1.2.3/hook");
        assertRejected("http://192.168.0.10/hook");
        assertRejected("http://172.16.5.5/hook");
        // 云厂商元数据地址（链路本地段）
        assertRejected("http://169.254.169.254/latest/meta-data/");
        assertRejected("http://100.100.100.200/latest/meta-data/");
    }

    @Test
    void rejectsNonHttpSchemeAndMalformedAuthority() {
        assertRejected("ftp://203.0.113.10/hook");
        assertRejected("file:///etc/passwd");
        assertRejected("203.0.113.10/hook");
        assertRejected("http://admin:pw@203.0.113.10/hook");
        assertRejected("http://this-host-does-not-exist.invalid/hook");
        assertRejected("");
        assertRejected(null);
    }

    private void assertRejected(String url) {
        assertThatThrownBy(() -> ExternalUrlValidator.requirePublicHttpUrl(url))
                .as("应拒绝地址: %s", url)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
