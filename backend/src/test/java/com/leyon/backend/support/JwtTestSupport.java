package com.leyon.backend.support;

import com.leyon.backend.service.AccountCredentialService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 令牌相关单测的公共夹具
 * <p>
 * v2.42 起 {@code JwtUtil} 会回查账号凭据版本；凡是"只想验别的性质"的测试，
 * 都需要一个"账号存在且版本匹配"的桩，否则会在与用例无关的那一关被判死。
 *
 * @author leyon
 */
public final class JwtTestSupport {

    private JwtTestSupport() {
    }

    /** 恒判"凭据仍有效"的账号侧桩：等价于 v2.42 之前的行为，不引入额外断言 */
    public static AccountCredentialService alwaysLiveCredentials() {
        AccountCredentialService credentials = mock(AccountCredentialService.class);
        when(credentials.isCredentialLive(any(), anyInt())).thenReturn(true);
        when(credentials.currentVersion(any())).thenReturn(0);
        return credentials;
    }
}
