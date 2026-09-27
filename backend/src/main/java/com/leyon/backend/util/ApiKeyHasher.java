package com.leyon.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * API Key 的存储形态：SHA-256 hex（v2.45）
 * <p>
 * 明文 Key 是 64 位十六进制（两段 UUIDv4，合计约 244 位熵），因此**不加 pepper 也成立**：
 * 输入空间大到"预计算彩虹表"没有意义，哈希在这里要挡的不是"猜中 Key"，而是"库外泄＝凭据外泄"。
 * 若将来把 Key 改成人工可读的短词，这个前提就不成立了，届时要换成 HMAC + 服务端密钥。
 * <p>
 * 刻意与 MySQL 的 {@code SHA2(x, 256)} 同形（小写 hex、64 字符），迁移 0006 才能用
 * {@code SHA2(app_key, 256)} 把存量明文一次性回填成哈希——两侧算出来的串必须逐字节相等，
 * 否则老 Key 会在上线瞬间全部失效。
 *
 * @author leyon
 */
public final class ApiKeyHasher {

    /** 与 SHA2(x,256) 一致的输出长度（hex 字符数） */
    public static final int HEX_LENGTH = 64;

    private ApiKeyHasher() {
    }

    /**
     * 计算 Key 的存储哈希。
     *
     * @param apiKey 明文 Key（UTF-8 参与哈希）
     * @return 64 位小写十六进制；入参为 null 时返回 null
     */
    public static String hash(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JLS 要求每个实现都支持的算法；走到这里说明运行时被裁剪，不能静默降级成明文存储
            throw new IllegalStateException("SHA-256 不可用，无法安全存储 API Key", e);
        }
    }
}
