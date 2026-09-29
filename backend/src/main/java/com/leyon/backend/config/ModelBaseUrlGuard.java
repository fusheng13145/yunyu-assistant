package com.leyon.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 模型出站地址自检（v2.62 · C-128）
 * <p>
 * Spring AI 1.0.0 的 {@code spring.ai.openai.chat.completions-path} 默认值自带 {@code /v1}
 * （本地 jar 的 {@code spring-configuration-metadata.json} 实测 defaultValue 为
 * {@code /v1/chat/completions}），而历史口径把 {@code OPENAI_BASE_URL} 写成
 * {@code https://platform.deepseek.com/v1}，两者相加会合成出
 * {@code /v1/v1/chat/completions}。失效形态是"每次对话都失败"，报错却发生在上游 404，
 * 离配置最远、最难反推——公网实例还没有真实 Key，连失败都看不到。
 * <p>
 * 与另外两个守卫的分工：{@code CredentialPlaceholderGuard} 与 {@code ManagementAddressGuard}
 * 拒绝启动（缺凭据会静默降级、全网卡 actuator 是安全边界），本类只告警不阻断——
 * base-url 误配只影响对话一条链路，把配置笔误放大成整站起不来反而更难定位。
 *
 * @author leyon
 */
@Component
public class ModelBaseUrlGuard {

    private static final Logger logger = LoggerFactory.getLogger(ModelBaseUrlGuard.class);

    /** 上游默认出站路径，自带 /v1 ⇒ base-url 只能是主机根 */
    static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final Environment environment;

    public ModelBaseUrlGuard(Environment environment) {
        this.environment = environment;
    }

    /**
     * 启动时把"配置相加后的真实出站地址"打进日志，误配时点名修法
     */
    @PostConstruct
    public void validate() {
        String baseUrl = environment.getProperty("spring.ai.openai.base-url");
        String completionsPath = environment.getProperty("spring.ai.openai.chat.completions-path");
        String warning = check(baseUrl, completionsPath);
        if (warning != null) {
            logger.warn(warning);
            return;
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            logger.info("模型出站地址: {}{}", trimTrailingSlash(baseUrl), effectivePath(completionsPath));
        }
    }

    /**
     * 纯判定逻辑，便于单测
     *
     * @return 误配说明（含合成后的出站地址）；合规或无从判定时返回 null
     */
    static String check(String baseUrl, String completionsPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String composed = trimTrailingSlash(baseUrl) + effectivePath(completionsPath);
        if (!composed.contains("/v1/v1")) {
            return null;
        }
        return "模型地址疑似多写了一段 /v1 ⇒ chat 请求实际会发往 " + composed
                + "（spring.ai.openai.base-url / OPENAI_BASE_URL 只需主机根，"
                + "spring.ai.openai.chat.completions-path 默认已带 /v1）。"
                + "请改 OPENAI_BASE_URL=https://<供应商主机>（不带 /v1），"
                + "或显式设 spring.ai.openai.chat.completions-path=/chat/completions。"
                + "本项只告警不阻断启动。";
    }

    private static String effectivePath(String completionsPath) {
        return completionsPath == null || completionsPath.isBlank()
                ? DEFAULT_COMPLETIONS_PATH : completionsPath;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
