package com.leyon.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ManagementAddressGuard 单测（v2.31）
 * <p>
 * 覆盖 Boot 自身不报错、但会把免鉴权的 /actuator 绑到全网卡的那一类配置组合。
 *
 * @author leyon
 */
class ManagementAddressGuardTest {

    @Test
    @DisplayName("独立管理端口 + 未指定监听地址 ⇒ 拒绝启动并给出可执行的修法")
    void separatePortWithoutAddressIsRejected() {
        String violation = ManagementAddressGuard.check(8080, 9091, null);

        assertNotNull(violation);
        assertTrue(violation.contains("MANAGEMENT_SERVER_ADDRESS"), "应指明要设哪个变量: " + violation);
        assertTrue(violation.contains("9091"), "应指明违规端口: " + violation);
    }

    @Test
    @DisplayName("空串与缺失同等对待（.env 里写 MANAGEMENT_SERVER_ADDRESS= 也算未指定）")
    void blankAddressIsRejected() {
        assertNotNull(ManagementAddressGuard.check(8080, 9091, "   "));
    }

    @Test
    @DisplayName("独立管理端口 + 回环地址 ⇒ 合规")
    void separatePortWithLoopbackAddressIsAccepted() {
        assertNull(ManagementAddressGuard.check(8080, 9091, "127.0.0.1"));
    }

    @Test
    @DisplayName("独立管理端口 + 显式全网卡 ⇒ 合规（显式即责任归属明确，如容器内反代访问）")
    void explicitAllInterfacesIsAccepted() {
        assertNull(ManagementAddressGuard.check(8080, 9091, "0.0.0.0"));
    }

    @Test
    @DisplayName("与业务同端口 ⇒ 本守卫不判定（该方向由 Boot 自己拒绝）")
    void samePortIsNotJudgedHere() {
        assertNull(ManagementAddressGuard.check(8080, 8080, null));
        assertNull(ManagementAddressGuard.check(8080, null, null));
    }

    @Test
    @DisplayName("随机端口（server.port=0）不误报")
    void randomPortIsNotJudged() {
        assertNull(ManagementAddressGuard.check(0, 0, null));
    }

    @Test
    @DisplayName("validate() 读取真实配置键并在违规时抛 IllegalStateException")
    void validateThrowsOnLiveProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.port", "8091")
                .withProperty("management.server.port", "9091");

        assertThrows(IllegalStateException.class, new ManagementAddressGuard(env)::validate);
    }

    @Test
    @DisplayName("默认配置（同端口、address 为 null）不拦截——即 v2.30 回落后应能启动的路径")
    void defaultConfigStarts() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.port", "8091")
                .withProperty("management.server.port", "8091");

        assertDoesNotThrow(() -> new ManagementAddressGuard(env).validate());
    }
}
