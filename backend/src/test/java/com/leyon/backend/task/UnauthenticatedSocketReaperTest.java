package com.leyon.backend.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未认证 WebSocket 连接回收器测试
 * 覆盖"到期关闭 / 认证成功摘除 / 已关闭连接静默清理"三条路径（对应实测缺陷 C-65）
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class UnauthenticatedSocketReaperTest {

    /** 存活上限：30 秒 */
    private static final long LIMIT_MS = 30_000L;

    private static final CloseStatus EXPECTED_CLOSE =
            CloseStatus.POLICY_VIOLATION.withReason("unauthenticated connection expired");

    @Mock
    private WebSocketSession session;

    private final Map<String, Object> attributes = new HashMap<>();
    private UnauthenticatedSocketReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new UnauthenticatedSocketReaper(LIMIT_MS);
        lenient().when(session.getId()).thenReturn("ws-1");
        lenient().when(session.getAttributes()).thenReturn(attributes);
    }

    /** 把连接建立时间往前推，模拟已存活超过上限 */
    private void age(long ms) {
        long bornAt = (Long) attributes.get(UnauthenticatedSocketReaper.ATTR_CONNECTED_AT);
        attributes.put(UnauthenticatedSocketReaper.ATTR_CONNECTED_AT, bornAt - ms);
    }

    @SuppressWarnings("unchecked")
    private Map<String, WebSocketSession> pending() throws Exception {
        Field field = UnauthenticatedSocketReaper.class.getDeclaredField("pending");
        field.setAccessible(true);
        return (Map<String, WebSocketSession>) field.get(reaper);
    }

    @Test
    void expiredUnauthenticatedSocket_isClosedAndUnregistered() throws Exception {
        reaper.watch(session);
        assertNotNull(attributes.get(UnauthenticatedSocketReaper.ATTR_CONNECTED_AT), "watch 应写入建立时间戳");
        when(session.isOpen()).thenReturn(true);
        age(LIMIT_MS + 1);

        reaper.reapExpired();

        verify(session).close(EXPECTED_CLOSE);
        assertTrue(pending().isEmpty(), "关闭后登记应被摘除");
    }

    @Test
    void socketWithinLimit_isNotClosed() throws Exception {
        reaper.watch(session);
        when(session.isOpen()).thenReturn(true);
        age(LIMIT_MS - 1);

        reaper.reapExpired();

        verify(session, never()).close(EXPECTED_CLOSE);
        assertEquals(1, pending().size());
    }

    @Test
    void afterRelease_socketIsNoLongerReaped() throws Exception {
        reaper.watch(session);
        reaper.release("ws-1");
        age(LIMIT_MS + 60_000);

        reaper.reapExpired();

        verify(session, never()).close(EXPECTED_CLOSE);
        assertTrue(pending().isEmpty());
    }

    @Test
    void alreadyClosedSocket_isDroppedWithoutCloseAttempt() throws Exception {
        reaper.watch(session);
        when(session.isOpen()).thenReturn(false);
        age(LIMIT_MS + 1);

        reaper.reapExpired();

        verify(session, never()).close(EXPECTED_CLOSE);
        assertTrue(pending().isEmpty(), "本条登记应已清理");
        // 再次扫描时把 isOpen 翻回 true：若首次扫描未摘除登记，此处就会误关一条好连接
        lenient().when(session.isOpen()).thenReturn(true);
        reaper.reapExpired();
        verify(session, never()).close(EXPECTED_CLOSE);
    }

    @Test
    void missingConnectedAt_dropsRegistrationWithoutClose() throws Exception {
        reaper.watch(session);
        attributes.remove(UnauthenticatedSocketReaper.ATTR_CONNECTED_AT);

        reaper.reapExpired();

        verify(session, never()).close(EXPECTED_CLOSE);
        assertTrue(pending().isEmpty());
    }

    @Test
    void isExpired_usesStrictGreaterThanLimit() {
        long now = 1_000_000L;
        assertFalse(UnauthenticatedSocketReaper.isExpired(now, now - LIMIT_MS, LIMIT_MS), "恰好等于上限不判定为过期");
        assertTrue(UnauthenticatedSocketReaper.isExpired(now, now - LIMIT_MS - 1, LIMIT_MS));
    }
}
