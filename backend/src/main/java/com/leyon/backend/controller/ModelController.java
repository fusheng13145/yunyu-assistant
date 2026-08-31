package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型字典接口
 * 服务端下发可用 LLM 模型列表，供前端"模型参数配置"下拉选择
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

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

    /**
     * 获取模型列表
     */
    @GetMapping
    public ApiResponse<List<ModelInfo>> list() {
        return ApiResponse.success(MODELS);
    }
}
