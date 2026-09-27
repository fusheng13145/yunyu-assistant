package com.leyon.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流计数单点单测（v2.45 从 {@code RateLimitInterceptorTest} 之下移上来）
 * <p>
 * 拦截器测试测的是"URI 落在哪一档 + 429 响应形状"；本类测的是档位表本身与桶键语义，
 * 其中只有 v2.45 新增的 OPEN_WS 档没有第二个调用方可依赖——握手侧（{@code OpenApiWebSocketAuthInterceptor}）
 * 直接调本服务，一旦档名拼错或容量回退，表现是"语音握手悄悄不限流"，HTTP 侧的测试全绿也发现不了。
 * <p>
 * 一律走内存实现（{@code redisEnabled} 默认 false）。窗口为 60 秒，故这里只断言"窗口内"的行为，
 * 不做等待窗口滚动的时序断言。
 *
 * @author leyon
 */
class RateLimitServiceTest {

    private final RateLimitService service = new RateLimitService();

    /** 连续申请 n 次，返回第 n 次是否放行 */
    private boolean acquire(RateLimitService.Tier tier, String ip, int times) {
        RateLimitService.Decision decision = null;
        for (int i = 0; i < times; i++) {
            decision = service.tryAcquire(tier, ip);
        }
        assertNotNull(decision);
        return decision.allowed();
    }

    @Nested
    @DisplayName("档位表")
    class TierTable {

        @Test
        @DisplayName("容量与口径一致：AUTH 5 / EXPENSIVE 30 / OPEN_WS 10")
        void capacities() {
            assertEquals(5, RateLimitService.Tier.AUTH.capacity());
            assertEquals(30, RateLimitService.Tier.EXPENSIVE.capacity());
            assertEquals(10, RateLimitService.Tier.OPEN_WS.capacity());
        }

        @Test
        @DisplayName("MVC 注册清单覆盖两个 HTTP 档的全部端点，且不含握手路径")
        void mvcPathPatternsCoverHttpTiers() {
            for (String uri : new String[]{"/api/auth/login", "/api/auth/register",
                    "/api/auth/refresh", "/api/auth/password", "/api/open/chat", "/api/open/call",
                    "/api/ragflow/retrieval-test", "/api/call-records/x/recording"}) {
                assertNotNull(RateLimitService.Tier.resolveMvc(uri), "端点未归档，根本不会进拦截器：" + uri);
                assertTrue(java.util.Arrays.stream(RateLimitService.Tier.mvcPathPatterns())
                                .anyMatch(pattern -> new org.springframework.util.AntPathMatcher()
                                        .match(pattern, uri)),
                        "端点已归档但未出现在 MVC 注册清单：" + uri);
            }
            for (String pattern : RateLimitService.Tier.mvcPathPatterns()) {
                assertFalse(pattern.contains("ws-voice"), "握手路径不该经 MVC 拦截器：" + pattern);
            }
        }

        @Test
        @DisplayName("OPEN_WS 无 MVC 模式：resolveMvc 永不命中它（防「加了档就以为限了流」）")
        void openWsIsNotMvcRoutable() {
            assertNull(RateLimitService.Tier.resolveMvc("/api/open/ws-voice/socket-1"));
            assertEquals(0, RateLimitService.Tier.OPEN_WS.patternsForMvc().length);
        }

        @Test
        @DisplayName("普通业务路径不归档")
        void ordinaryPathHasNoTier() {
            assertNull(RateLimitService.Tier.resolveMvc("/api/assistants"));
        }

        @Test
        @DisplayName("录音下载与上传同形状路径都落高成本档（带路径变量的模式）")
        void recordingPatternMatchesBothMethods() {
            assertEquals(RateLimitService.Tier.EXPENSIVE,
                    RateLimitService.Tier.resolveMvc("/api/call-records/c-9/recording"));
        }
    }

    @Nested
    @DisplayName("桶键语义")
    class BucketKey {

        @Test
        @DisplayName("OPEN_WS 第 11 次拒绝：10 次/分钟/来源，且 Retry-After 至少 1 秒")
        void openWsCapacityEnforced() {
            assertTrue(acquire(RateLimitService.Tier.OPEN_WS, "203.0.113.10", 10), "前 10 次握手应放行");
            RateLimitService.Decision eleventh = service.tryAcquire(RateLimitService.Tier.OPEN_WS, "203.0.113.10");
            assertFalse(eleventh.allowed());
            assertTrue(eleventh.retryAfterSeconds() >= 1L, "拒绝必须给出可重试秒数");
        }

        @Test
        @DisplayName("档间互不连坐：同一来源打满认证档后仍能握手，反之亦然")
        void tiersAreIndependent() {
            assertTrue(acquire(RateLimitService.Tier.AUTH, "203.0.113.11", 5));
            assertFalse(service.tryAcquire(RateLimitService.Tier.AUTH, "203.0.113.11").allowed());
            assertTrue(service.tryAcquire(RateLimitService.Tier.OPEN_WS, "203.0.113.11").allowed(),
                    "握手额度不该被 HTTP 认证请求耗尽");
            assertTrue(service.tryAcquire(RateLimitService.Tier.EXPENSIVE, "203.0.113.11").allowed());
        }

        @Test
        @DisplayName("来源间互不连坐：一个来源打满不影响另一个")
        void sourcesAreIndependent() {
            assertTrue(acquire(RateLimitService.Tier.OPEN_WS, "203.0.113.12", 10));
            assertTrue(service.tryAcquire(RateLimitService.Tier.OPEN_WS, "203.0.113.13").allowed());
            assertFalse(service.tryAcquire(RateLimitService.Tier.OPEN_WS, "203.0.113.12").allowed());
        }

        @Test
        @DisplayName("来源为空串时仍成独立一桶而不是共享 null 桶：握手侧拿不到地址也不能变成「所有人共桶」")
        void unknownSourceIsItsOwnBucket() {
            assertTrue(acquire(RateLimitService.Tier.OPEN_WS, "unknown", 10));
            assertFalse(service.tryAcquire(RateLimitService.Tier.OPEN_WS, "unknown").allowed());
            assertTrue(service.tryAcquire(RateLimitService.Tier.OPEN_WS, "203.0.113.14").allowed());
        }
    }
}
