package com.leyon.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * actuator 独立管理端口的监听地址守卫（v2.31）
 * <p>
 * Spring Boot 只在"管理端口与业务端口相同"时校验 address（此时设了 address 会直接抛异常），
 * 反过来"端口独立但没设 address"它是默许的——{@code ManagementWebServerFactoryCustomizer}
 * 无条件用 {@code management.server.address}（可为 null）覆盖连接器地址，
 * <b>不会</b>继承 {@code server.address}。后果是：业务端口按 {@code SERVER_ADDRESS=127.0.0.1}
 * 收进本机，而免鉴权的 {@code /actuator} 却绑到 0.0.0.0 全网卡，比不分离端口更糟。
 * 该配置组合没有崩溃迹象，只能靠启动时断言拦下。
 * <p>
 * 注意断言时机：管理端口的 Web 服务器在容器 {@code onRefresh} 阶段创建、{@code finishRefresh} 才启动，
 * 本 Bean 的 {@code @PostConstruct} 介于两者之间，因此违规时 /actuator 一次都不会监听。
 *
 * @author leyon
 */
@Component
public class ManagementAddressGuard {

    private static final Logger logger = LoggerFactory.getLogger(ManagementAddressGuard.class);

    private final Environment environment;

    public ManagementAddressGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * 启动时校验"独立管理端口必须显式指定监听地址"
     */
    @PostConstruct
    public void validate() {
        Integer serverPort = environment.getProperty("server.port", Integer.class, 8080);
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        String managementAddress = environment.getProperty("management.server.address");
        String violation = check(serverPort, managementPort, managementAddress);
        if (violation != null) {
            throw new IllegalStateException(violation);
        }
        if (isSeparate(serverPort, managementPort)) {
            logger.info("actuator 已独立于业务端口（管理 {} / 业务 {}），监听地址: {}",
                    managementPort, serverPort, managementAddress);
        }
    }

    /**
     * 纯判定逻辑，便于单测
     *
     * @return 违规说明；配置合规时返回 null
     */
    static String check(Integer serverPort, Integer managementPort, String managementAddress) {
        if (!isSeparate(serverPort, managementPort)) {
            return null;
        }
        if (managementAddress == null || managementAddress.isBlank()) {
            return "actuator 使用独立管理端口 " + managementPort + " 但未指定 management.server.address"
                    + " ⇒ /actuator 会绑定所有网卡（Spring Boot 不会让它继承 server.address）。"
                    + "单机或内网部署请设 MANAGEMENT_SERVER_ADDRESS=127.0.0.1；"
                    + "确需全网卡监听（如容器内由反代访问）则显式设 MANAGEMENT_SERVER_ADDRESS=0.0.0.0";
        }
        return null;
    }

    private static boolean isSeparate(Integer serverPort, Integer managementPort) {
        return managementPort != null && serverPort != null && !managementPort.equals(serverPort);
    }
}
