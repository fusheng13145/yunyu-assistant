package com.leyon.backend.service;

import com.leyon.backend.entity.Assistant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 助手级成本参数钳制策略（唯一判据入口）
 *
 * 配额按"条数"计量，而单条成本由助手配置决定：模型名、最大输出 Token、每条消息都要注入的人设提示词。
 * v2.42 评估登记为候选 ㉔——这三项此前全链透传，一条超长人设或一个超大 maxTokens 就能把按条计量的配额
 * 换成任意大的真实开销。本类把三者收敛到一个入口：
 * <ul>
 *   <li>{@link #runtime(Assistant)}：<b>读侧</b>钳制。装配发生在对话/通话进行中，此处拒绝会把已有脏数据的
 *       助手整体冻住，故按"回落 + 截断"处理；</li>
 *   <li>{@link #validateForWrite(Assistant)}：<b>写侧</b>拒绝。HTTP 请求-响应通道能把原因讲清楚，
 *       静默改写用户刚填的配置反而不可诊断；</li>
 *   <li>{@link #clampForStorage(Assistant)}：<b>落库前</b>钳制。WS 收尾会自动回写人设，那条路径没有可拒绝的
 *       请求方，只能钳制。</li>
 * </ul>
 * 三个入口共用同一组边界，不在别处再算一遍。
 *
 * @author leyon
 */
@Component
public class AssistantPolicy {

    private static final Logger log = LoggerFactory.getLogger(AssistantPolicy.class);

    /**
     * 单条回复的最大输出 Token 上限。刻意与 {@code ChatService.MAX_INPUT_CHARS} 同形取代码常量：
     * 这是成本边界而非运维旋钮，做成配置项只会被复制成第二份口径。
     * 取值等于清单内模型自身的输出上限 ⇒ 对合法配置零影响，只砍掉模型本来也给不出的值。
     */
    public static final int MAX_OUTPUT_TOKENS = 8192;

    /** 温度合法域，由 OpenAI 兼容协议定义 */
    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;

    /** 人设提示词上限：它每条消息都注入，故取"单条输入上限的 2 倍"，不让配置本身比一轮对话更贵 */
    public static final int MAX_PERSONALITY_CHARS = 4000;

    private final ModelCatalog modelCatalog;

    public AssistantPolicy(ModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    /** 钳制后的助手级运行时参数（null 一律表示"未指定，走服务端默认"） */
    public record Runtime(String model, Double temperature, Integer maxTokens, String personality) {}

    public Runtime runtime(Assistant assistant) {
        if (assistant == null) {
            return new Runtime(null, null, null, null);
        }
        String model = assistant.getModelName();
        if (StringUtils.hasText(model) && !modelCatalog.isSupported(model)) {
            log.warn("助手 {} 配置的模型 {} 不在清单内，按服务端默认模型处理", assistant.getId(), model.trim());
            model = null;
        }
        return new Runtime(
                StringUtils.hasText(model) ? model.trim() : null,
                clampTemperature(assistant.getTemperature()),
                clampMaxTokens(assistant.getMaxTokens()),
                clampPersonality(assistant.getPersonality()));
    }

    /**
     * 写侧校验：越界即拒绝，原因带上下限与清单，便于用户直接改对
     *
     * @throws IllegalArgumentException 任一项越界（此时不产生任何写库动作）
     */
    public void validateForWrite(Assistant assistant) {
        if (assistant == null) {
            return;
        }
        String model = assistant.getModelName();
        if (StringUtils.hasText(model) && !modelCatalog.isSupported(model)) {
            throw new IllegalArgumentException("模型 " + model.trim() + " 不在可用清单内（可选："
                    + modelCatalog.supportedIds() + "；留空则使用服务端默认模型）");
        }
        Double temperature = assistant.getTemperature();
        if (temperature != null) {
            if (!Double.isFinite(temperature)) {
                throw new IllegalArgumentException("温度必须是 0~2 之间的有限数值");
            }
            if (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
                throw new IllegalArgumentException("温度需在 " + MIN_TEMPERATURE + "~" + MAX_TEMPERATURE
                        + " 之间（当前 " + temperature + "）");
            }
        }
        Integer maxTokens = assistant.getMaxTokens();
        if (maxTokens != null) {
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("最大输出 Token 需为正整数（当前 " + maxTokens + "）");
            }
            if (maxTokens > MAX_OUTPUT_TOKENS) {
                throw new IllegalArgumentException("最大输出 Token 不能超过 " + MAX_OUTPUT_TOKENS
                        + "（当前 " + maxTokens + "）");
            }
        }
        String personality = assistant.getPersonality();
        if (personality != null && personality.length() > MAX_PERSONALITY_CHARS) {
            throw new IllegalArgumentException("人设提示词不能超过 " + MAX_PERSONALITY_CHARS + " 字（当前 "
                    + personality.length() + " 字）");
        }
    }

    /**
     * 落库前钳制：就地把实体字段改到边界内，保证库里不可能存在超限值（含 WS 收尾自动回写人设那条路径）
     */
    public void clampForStorage(Assistant assistant) {
        if (assistant == null) {
            return;
        }
        Runtime rt = runtime(assistant);
        assistant.setModelName(rt.model());
        assistant.setTemperature(rt.temperature());
        assistant.setMaxTokens(rt.maxTokens());
        assistant.setPersonality(rt.personality());
    }

    private Double clampTemperature(Double temperature) {
        if (temperature == null || !Double.isFinite(temperature)) {
            return null;
        }
        if (temperature < MIN_TEMPERATURE) {
            return MIN_TEMPERATURE;
        }
        if (temperature > MAX_TEMPERATURE) {
            return MAX_TEMPERATURE;
        }
        return temperature;
    }

    private Integer clampMaxTokens(Integer maxTokens) {
        if (maxTokens == null || maxTokens <= 0) {
            return null;
        }
        return Math.min(maxTokens, MAX_OUTPUT_TOKENS);
    }

    private String clampPersonality(String personality) {
        if (personality == null || personality.length() <= MAX_PERSONALITY_CHARS) {
            return personality;
        }
        log.warn("助手人设 {} 字超过上限 {} 字，已按上限截断注入", personality.length(), MAX_PERSONALITY_CHARS);
        return personality.substring(0, MAX_PERSONALITY_CHARS);
    }
}
