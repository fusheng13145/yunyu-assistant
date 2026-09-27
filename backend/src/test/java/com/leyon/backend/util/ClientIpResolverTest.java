package com.leyon.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ClientIpResolver 单测（v2.44 客户端身份收口）
 * <p>
 * 锁死四件事：①默认（未启用反代信任）时**任何代理头都不参与判定**，桶键只能是连接层地址；
 * ②启用反代信任时取 XFF 的**右端起第一个非空条目**（受信代理追加的那一跳），而不是攻击者
 * 自己写在最左边的伪造值；③两条路径都必须有非空兜底，否则桶键会退化成共享桶；
 * ④生产注入路径（构造参数 → 实例 resolve）真的按配置取值。
 * 另有一条差分用例记录"旧实现会给出不同答案"，作为改回旧取法的鉴别证据。
 *
 * @author leyon
 */
class ClientIpResolverTest {

    private MockHttpServletRequest request(String remoteAddr, String... xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        for (String header : xff) {
            request.addHeader("X-Forwarded-For", header);
        }
        return request;
    }

    @Nested
    @DisplayName("未启用反代信任（默认）：代理头一律无效")
    class DirectMode {

        @Test
        @DisplayName("XFF 存在也只认连接层地址（伪造头不能换取新桶）")
        void ignoresForwardedFor() {
            assertEquals("203.0.113.7",
                    ClientIpResolver.resolve(request("203.0.113.7", "1.2.3.4"), false));
        }

        @Test
        @DisplayName("多值 XFF 同样无效（v2.43 前的缺陷形状：直取第一段）")
        void ignoresMultiValueForwardedFor() {
            assertEquals("203.0.113.7",
                    ClientIpResolver.resolve(request("203.0.113.7", "9.9.9.9, 8.8.8.8"), false));
        }

        @Test
        @DisplayName("无连接层地址时回兜底值，不返回 null（null 会让所有缺地址请求共用一桶）")
        void fallsBackToUnknown() {
            assertEquals("unknown", ClientIpResolver.resolve(request(null), false, "unknown"));
        }
    }

    @Nested
    @DisplayName("启用反代信任：按右端起跳数解析")
    class ProxiedMode {

        @Test
        @DisplayName("单跳受信代理：取最左条目（此时它就是代理看到的真实对端）")
        void singleHopTakesLeftmost() {
            assertEquals("203.0.113.9",
                    ClientIpResolver.resolve(request("10.0.0.2", "203.0.113.9"), true));
        }

        @Test
        @DisplayName("客户端伪造左段 + 受信代理追加右段：取右段，伪造值不进桶键")
        void spoofedLeftSegmentIsIgnored() {
            assertEquals("203.0.113.10",
                    ClientIpResolver.resolve(request("10.0.0.2", "1.1.1.1, 203.0.113.10"), true));
        }

        @Test
        @DisplayName("跳数超过链长时收敛到最左条目，不越界也不退回连接层内网地址")
        void clampsWhenHopsExceedChain() {
            assertEquals("203.0.113.11",
                    ClientIpResolver.resolve(request("10.0.0.2", "203.0.113.11"), true, "unknown", 3));
        }

        @Test
        @DisplayName("空条目（连续逗号 / 尾随逗号）跳过，不产生空桶键")
        void skipsBlankSegments() {
            assertEquals("203.0.113.12",
                    ClientIpResolver.resolve(request("10.0.0.2", "1.1.1.1, , 203.0.113.12,"), true));
        }

        @Test
        @DisplayName("启用信任但无 XFF：回连接层地址（本机直连场景）")
        void fallsBackToRemoteAddr() {
            assertEquals("10.0.0.3", ClientIpResolver.resolve(request("10.0.0.3"), true));
        }

        @Test
        @DisplayName("XFF 全为空段且无连接层地址：回兜底值")
        void fallsBackWhenEverythingBlank() {
            assertEquals("unknown", ClientIpResolver.resolve(request(null, " , "), true, "unknown"));
        }

        @Test
        @DisplayName("实例形态（生产注入的就是这条路径）：构造参数真的参与判定")
        void instanceHonoursInjectedConfig() {
            MockHttpServletRequest twoProxies = request("10.0.0.2", "203.0.113.13, 10.0.0.5");
            assertEquals("203.0.113.13", new ClientIpResolver(true, 2).resolve(twoProxies),
                    "两跳受信代理应取右起第二项，否则桶键落在中间代理上");
            assertEquals("10.0.0.2", new ClientIpResolver(false, 2).resolve(twoProxies),
                    "未开信任时即使传了跳数也不得读代理头");
        }

        /**
         * 差分证据（替代变异测试）：把 v2.43 及以前的取法在本用例里重算一遍，
         * 断言它与现在的判定**必然不同**。这样一旦有人把"直取 XFF 第一段"改回来，
         * 不只是本用例失败，而是这里显式记录下"两条实现给出不同答案"这一事实。
         */
        @Test
        @DisplayName("差分证据：旧实现（直取 XFF 第一段）与本组断言给出的值不同")
        void legacyLeftmostParsingDiffers() {
            MockHttpServletRequest forged = request("203.0.113.7", "1.1.1.1, 8.8.8.8");
            String legacy = forged.getHeader("X-Forwarded-For").split(",")[0].trim();
            assertEquals("1.1.1.1", legacy, "旧取法拿到的是客户端自填值");
            assertNotEquals(legacy, ClientIpResolver.resolve(forged, false),
                    "两值相同说明本批断言对'改回旧取法'没有鉴别力");
            assertEquals("203.0.113.7", ClientIpResolver.resolve(forged, false));
        }
    }
}
