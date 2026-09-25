package com.leyon.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 凭据占位值守卫（v2.34）
 * <p>
 * {@code .env.example} 用字面量占位标记提醒必填项，但照抄成 {@code .env} 后直接启动时，
 * 这些值会以"看似已配置"的姿态进入连接池/模型调用，报错发生在离配置很远的地方
 * （MySQL 拒绝连接、401 from LLM），维护者却要在那里反推原因。
 * 本守卫在启动最早期把"未注入 / 空 / 仍是占位标记"三种形态统一翻译成点名的可执行修法。
 * <p>
 * 注意与既有校验的分工：JWT 密钥的长度与默认值检测在 {@code JwtUtil}，
 * 管理端口监听地址在 {@code ManagementAddressGuard}；本类只管数据源与模型 Key 的占位形态。
 * CI 的哑值（非空、非占位标记）不在拦截范围——单测口径要求零凭据可跑。
 *
 * @author leyon
 */
@Component
public class CredentialPlaceholderGuard {

    private static final Logger logger = LoggerFactory.getLogger(CredentialPlaceholderGuard.class);

    /** .env.example 模板中必填项的占位标记 */
    static final String PLACEHOLDER = "[REQUIRED]";

    private final Environment environment;

    public CredentialPlaceholderGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * 启动时校验数据源与模型 Key 不是占位形态
     */
    @PostConstruct
    public void validate() {
        String violation = check(
                resolve("spring.datasource.username"),
                resolve("spring.datasource.password"),
                resolve("spring.ai.openai.api-key"));
        if (violation != null) {
            throw new IllegalStateException(violation);
        }
        logger.info("凭据占位值守卫通过（DB 账号 / DB 密码 / 模型 Key 均已填写）");
    }

    /**
     * 读取属性值；yaml 里的 ${VAR} 因环境变量缺失而解析失败时按"未注入"处理，
     * 避免把 Spring 的占位符解析异常原样抛给使用者
     */
    private String resolve(String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 纯判定逻辑，便于单测
     *
     * @return 违规说明（点名所有违规变量）；全部合规时返回 null
     */
    static String check(String dbUser, String dbPassword, String openaiApiKey) {
        List<String> offenders = new ArrayList<>();
        if (isPlaceholderForm(dbUser)) {
            offenders.add("DB_USER");
        }
        if (isPlaceholderForm(dbPassword)) {
            offenders.add("DB_PASSWORD");
        }
        if (isPlaceholderForm(openaiApiKey)) {
            offenders.add("OPENAI_API_KEY");
        }
        if (offenders.isEmpty()) {
            return null;
        }
        return "启动阻断：以下凭据未注入、为空或仍是模板占位标记 " + PLACEHOLDER + "："
                + String.join("、", offenders)
                + "。请复制 .env.example 为 .env 并填入真实值（或经部署平台的环境变量注入），"
                + "再重启服务；本地快速起环境见 scripts/gen-dev-env.sh 与手册 5.3。";
    }

    private static boolean isPlaceholderForm(String value) {
        return value == null || value.isBlank() || value.contains(PLACEHOLDER);
    }
}
