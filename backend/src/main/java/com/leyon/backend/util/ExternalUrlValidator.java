package com.leyon.backend.util;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 外部回调地址安全校验（防 SSRF）
 * 用于服务端主动请求用户可配置地址的场景（当前为 Webhook 回调）：
 * 仅允许 http/https、无用户信息、无通配符主机，且主机解析后的每个 IP 都必须是公网可路由地址
 * （拒绝回环、私有网段、链路本地含云厂商元数据 169.254.169.254、CGNAT 100.64.0.0/10
 * 含阿里云元数据 100.100.100.200、IPv6 ULA fc00::/7、任意本地 0.0.0.0、组播）
 *
 * <p>残余风险：校验与实际发起请求之间仍存在 DNS 重绑定时间窗，
 * 生产环境应叠加出网策略（仅放行目标端口/网段）作为纵深防御。
 *
 * @author leyon
 */
public final class ExternalUrlValidator {

    /** 拒绝原因（对外统一文案，避免泄露内网拓扑） */
    private static final String REJECT_MESSAGE = "地址仅支持 http/https，且不允许指向本机或内网地址";

    private ExternalUrlValidator() {
    }

    /**
     * 校验地址可被服务端安全访问，非法则抛出 IllegalArgumentException
     *
     * @param rawUrl 待校验地址
     * @throws IllegalArgumentException 地址非法或指向非公网地址
     */
    public static void requirePublicHttpUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalArgumentException(REJECT_MESSAGE);
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(REJECT_MESSAGE);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        // 禁止 userinfo（user:pass@host 会被代理链误解析）与通配符主机
        if ((!scheme.equals("http") && !scheme.equals("https")) || !StringUtils.hasText(host)
                || uri.getUserInfo() != null || host.startsWith("*")) {
            throw new IllegalArgumentException(REJECT_MESSAGE);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // 主机名无法解析按非法处理（fail-closed）
            throw new IllegalArgumentException(REJECT_MESSAGE);
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new IllegalArgumentException(REJECT_MESSAGE);
            }
        }
    }

    /**
     * 是否为非公网地址
     */
    private static boolean isNonPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // 100.64.0.0/10 运营商级 NAT 共享段：JDK 不识别为内网，但阿里云元数据地址 100.100.100.200 落在此段
            return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
        }
        // IPv6 ULA fc00::/7：JDK 的 isSiteLocalAddress 只认已废弃的 fec0::/10，需单独拦截
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
