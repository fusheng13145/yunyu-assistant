package com.leyon.backend.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 未认证 WebSocket 连接的存活上限回收器（v2.32）
 * <p>
 * 为什么需要它：{@code /ws/**} 与 {@code /ws-voice/**} 允许<b>免令牌握手</b>（认证推迟到首条 auth 帧，
 * 前端正是这么用的），所以任何人不花任何凭据就能占住一条连接。而"空闲超时"在服务端并不存在：
 * Tomcat 10.1.24 里 {@code WsSession#checkExpiration()} 只被<b>客户端</b>容器
 * {@code WsWebSocketContainer#backgroundProcess()} 调用，服务端会话没人调，
 * {@code setMaxIdleTimeout()} 于服务端是空操作——实测一条握手后不发帧的连接存活超过 400 秒。
 * 公网多人站点上这等于把连接槽当作免费资源发放，故在应用层自己收口。
 * <p>
 * 只回收"始终没认证"的连接：认证成功即 {@link #release} 摘除登记，正常用户与通话不受任何影响。
 * <p>
 * 已知边界：本类跑在 Spring 默认的定时任务线程上（与归档、外呼超时、Webhook 重试同一线程池），
 * 那些任务若长时间占住线程，回收会顺延；实测扫描周期 10 秒、判定精度即为此值。
 *
 * @author leyon
 */
@Component
public class UnauthenticatedSocketReaper {

    private static final Logger logger = LoggerFactory.getLogger(UnauthenticatedSocketReaper.class);

    /** 连接建立时间戳（毫秒）在会话属性中的键 */
    public static final String ATTR_CONNECTED_AT = "unauthenticatedConnectedAt";

    /** 扫描周期：判定精度即为此值，最坏情况连接存活 上限 + 一个周期 */
    private static final long SCAN_PERIOD_MS = 10_000L;

    /** 关闭原因（WebSocket 关闭帧的 reason 走 ASCII，避免多字节截断） */
    private static final CloseStatus CLOSE_STATUS =
            CloseStatus.POLICY_VIOLATION.withReason("unauthenticated connection expired");

    private final Map<String, WebSocketSession> pending = new ConcurrentHashMap<>();

    private final long idleLimitMs;

    public UnauthenticatedSocketReaper(
            @Value("${app.ws.unauthenticated-idle-ms:30000}") long idleLimitMs) {
        this.idleLimitMs = idleLimitMs;
    }

    /**
     * 登记一条等待认证的连接（在 afterConnectionEstablished 里调用）
     */
    public void watch(WebSocketSession session) {
        session.getAttributes().put(ATTR_CONNECTED_AT, System.currentTimeMillis());
        pending.put(session.getId(), session);
    }

    /**
     * 摘除登记：认证成功或连接已关闭时调用，此后该连接不再受本回收器影响
     */
    public void release(String sessionId) {
        pending.remove(sessionId);
    }

    /**
     * 周期性关闭超期仍未认证的连接
     */
    @Scheduled(fixedDelay = SCAN_PERIOD_MS)
    public void reapExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, WebSocketSession> entry : pending.entrySet()) {
            WebSocketSession session = entry.getValue();
            Object connectedAt = session.getAttributes().get(ATTR_CONNECTED_AT);
            // 属性丢失或连接已关：本条登记已无意义，静默摘除
            if (!(connectedAt instanceof Long bornAt) || !session.isOpen()) {
                pending.remove(entry.getKey());
                continue;
            }
            if (!isExpired(now, bornAt, idleLimitMs)) {
                continue;
            }
            pending.remove(entry.getKey());
            logger.warn("未认证 WebSocket 连接超过 {}ms 存活上限，主动关闭：会话ID:{}, 远端:{}",
                    idleLimitMs, entry.getKey(), session.getRemoteAddress());
            closeQuietly(session);
        }
    }

    /**
     * 纯判定逻辑，便于单测
     */
    static boolean isExpired(long nowMs, long connectedAtMs, long limitMs) {
        return nowMs - connectedAtMs > limitMs;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CLOSE_STATUS);
        } catch (Exception e) {
            logger.warn("关闭超期未认证 WebSocket 连接失败：会话ID:{}", session.getId(), e);
        }
    }
}
