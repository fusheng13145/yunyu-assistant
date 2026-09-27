package com.leyon.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API Key 存储哈希单测（v2.45）
 * <p>
 * 这里要锁死的是**形态**，不是"安全性"：迁移 0006 用 MySQL 的 {@code SHA2(app_key,256)} 回填存量明文，
 * Java 侧算出的串必须与它逐字节相等，否则老 Key 在上线瞬间全部失效。故除 NIST 标准向量外，
 * 还用与生产同形的 64 位 hex Key（两段 UUID 拼接）取一组期望值——那才是真正会被回填的输入形状。
 *
 * @author leyon
 */
class ApiKeyHasherTest {

    @Nested
    @DisplayName("输出形态")
    class Shape {

        @Test
        @DisplayName("输出为 64 位小写十六进制（与 SHA2(x,256) 的 hex 形态一致）")
        void lowercaseHexOfFixedLength() {
            String hash = ApiKeyHasher.hash("any-key-shape-not-implemented-here");
            assertEquals(ApiKeyHasher.HEX_LENGTH, hash.length());
            assertTrue(hash.matches("^[0-9a-f]{64}$"), "应为小写 hex，实际：" + hash);
        }

        @Test
        @DisplayName("同一 Key 两次计算结果相同：鉴权按等值查库，非确定性实现会直接失配")
        void deterministic() {
            assertEquals(ApiKeyHasher.hash("stable-key"), ApiKeyHasher.hash("stable-key"));
        }

        @Test
        @DisplayName("不同 Key 哈希不同：唯一索引 uk_app_key_hash 依赖这一点")
        void distinctKeysDistinctHashes() {
            assertNotEquals(ApiKeyHasher.hash("key-a"), ApiKeyHasher.hash("key-b"));
            // 摘要按字节算 ⇒ 大小写不同的 Key 不再等价（v2.44 的字符串比较同样敏感，不是回归，
            // 但明文列删掉后"从库里抄一份 Key 发给客户"已不可能，故这条契约必须有断言撑着）
            assertNotEquals(ApiKeyHasher.hash("Key-A"), ApiKeyHasher.hash("key-a"));
        }

        @Test
        @DisplayName("null 输入返回 null，不抛异常也不产生\"空串的哈希\"")
        void nullReturnsNull() {
            assertNull(ApiKeyHasher.hash(null));
            // 空串是合法输入（非 null）：算出的是 SHA-256("")，调用方不会误当成"未提供 Key"
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    ApiKeyHasher.hash(""));
        }
    }

    @Nested
    @DisplayName("与标准实现交叉验证")
    class KnownVectors {

        @Test
        @DisplayName("NIST 标准向量：SHA-256(\"abc\")")
        void abcVector() {
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    ApiKeyHasher.hash("abc"));
        }

        @Test
        @DisplayName("生产形态向量：64 位 hex Key（两段 UUID 去连字符拼接）")
        void productionKeyShapeVector() {
            assertEquals("a8ae6e6ee929abea3afcfc5258c8ccd6f85273e0d4626d26c7279f3250f77c8e",
                    ApiKeyHasher.hash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        }
    }
}
