package com.leyon.backend.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 模型清单（全仓唯一一份）
 * 既下发给前端下拉（/api/models），也作为助手级模型名的白名单（{@link AssistantPolicy}），
 * 避免"界面给的选项服务端却接受任意值"这种清单只装饰不执行的状态（v2.54，手册 4.5 第 14 条）。
 *
 * @author leyon
 */
@Component
public class ModelCatalog {

    /**
     * 模型信息
     *
     * @param id          模型标识
     * @param name        展示名称
     * @param description 说明
     */
    public record ModelInfo(String id, String name, String description) {}

    /** 预置模型列表（OpenAI 兼容协议，随接入的大模型服务调整） */
    private static final List<ModelInfo> MODELS = List.of(
            new ModelInfo("deepseek-chat", "DeepSeek Chat", "通用对话模型，均衡速度与效果"),
            new ModelInfo("deepseek-reasoner", "DeepSeek Reasoner", "深度推理模型，适合复杂逻辑问题"),
            new ModelInfo("qwen-turbo", "通义千问 Turbo", "阿里云快速对话模型"),
            new ModelInfo("qwen-plus", "通义千问 Plus", "阿里云增强对话模型")
    );

    public List<ModelInfo> all() {
        return MODELS;
    }

    public boolean isSupported(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return false;
        }
        String target = modelName.trim();
        return MODELS.stream().anyMatch(m -> m.id().equals(target));
    }

    /** 供拒绝原因回显：让调用方一眼看到该填什么，而不是只看到"不合法" */
    public String supportedIds() {
        return MODELS.stream().map(ModelInfo::id).collect(Collectors.joining("/"));
    }
}
