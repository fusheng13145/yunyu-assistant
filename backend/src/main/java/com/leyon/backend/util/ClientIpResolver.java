package com.leyon.backend.util;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 客户端 IP 判定（v2.44）
 * <p>
 * 限流桶键与登录锁定维度都以"服务端能证明的来源"为准，因此默认一律只信连接层地址
 * （{@code getRemoteAddr()}）。代理头是客户端可任意伪造的输入面：v2.44 之前这里直取
 * {@code X-Forwarded-For} 的第一段，等于"每个请求都能自选自己落在哪个桶"——认证限流与
 * 用户名锁定双双失效。
 * <p>
 * 只有实例确实跑在反向代理后面时才把 {@code app.security.trust-proxy} 置 true；
 * 开了之后取 XFF 从右端起第 {@code trust-hops} 个非空条目（受信代理追加的那一跳），
 * 而不是最左段——攻击者可以在最左边写任意个伪造条目，但写不了受信代理追加的那一段。
 * {@code trust-hops} 默认 1，适配"一层 nginx/Caddy 终结 TLS"的形态；前置每多一跳受信代理
 * （如 CDN → LB → nginx）就要相应调大，否则桶键会落到中间代理的地址上。
 * <p>
 * 注意本类不读 {@code X-Real-IP}：那通常是 nginx 用 {@code $remote_addr} 设的单跳值，
 * 与"右端起跳数"这一套判据重复，且无法表达跳数，留着只会多一个伪造入口。
 *
 * @author leyon
 */
@Component
public class ClientIpResolver {

    /** 无连接层地址时的兜底值：宁可是固定一桶（限流偏严），也不要 null 造成的共享空桶 */
    public static final String UNKNOWN = "unknown";

    /** 是否信任反向代理追加的 X-Forwarded-For */
    private final boolean trustProxy;

    /** 受信代理跳数（trustProxy=true 时从右端起算） */
    private final int trustHops;

    public ClientIpResolver(@Value("${app.security.trust-proxy:false}") boolean trustProxy,
                            @Value("${app.security.trust-hops:1}") int trustHops) {
        this.trustProxy = trustProxy;
        this.trustHops = trustHops;
    }

    /** 按当前配置判定客户端 IP；无地址时返回 {@link #UNKNOWN} */
    public String resolve(HttpServletRequest request) {
        return resolve(request, trustProxy, UNKNOWN, trustHops);
    }

    /** 无配置形态的判定（单测与显式传参的调用方使用） */
    public static String resolve(HttpServletRequest request, boolean trustProxy) {
        return resolve(request, trustProxy, UNKNOWN, 1);
    }

    /** 无配置形态的判定，可指定兜底值 */
    public static String resolve(HttpServletRequest request, boolean trustProxy, String fallback) {
        return resolve(request, trustProxy, fallback, 1);
    }

    /**
     * 完整判定：{@code trustProxy=false} 时代理头完全不参与；
     * {@code true} 时从右端起跳过 {@code trustHops - 1} 个非空条目取那一跳。
     *
     * @param request    请求对象，可为 null
     * @param trustProxy 是否信任反向代理追加的 XFF
     * @param fallback   取不到任何地址时的返回值
     * @param trustHops  受信代理跳数，小于 1 时按 1 处理
     */
    public static String resolve(HttpServletRequest request, boolean trustProxy, String fallback, int trustHops) {
        if (request == null) {
            return fallback;
        }
        if (trustProxy) {
            String proxied = fromForwardedFor(request.getHeader("X-Forwarded-For"), trustHops);
            if (StringUtils.hasText(proxied)) {
                return proxied;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddr) ? remoteAddr : fallback;
    }

    /** 取 XFF 从右起第 hops 个非空条目；条目不足时收敛到最左一个非空条目 */
    private static String fromForwardedFor(String headerValue, int hops) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        String[] segments = headerValue.split(",");
        int target = Math.max(1, hops);
        int matched = 0;
        String lastNonBlank = null;
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i].trim();
            if (segment.isEmpty()) {
                continue;
            }
            lastNonBlank = segment;
            matched++;
            if (matched == target) {
                return segment;
            }
        }
        return lastNonBlank;
    }
}
