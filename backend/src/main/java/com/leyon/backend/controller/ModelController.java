package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.service.ModelCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型字典接口
 * 服务端下发可用 LLM 模型列表，供前端"模型参数配置"下拉选择。
 * 清单本身在 {@link ModelCatalog}——同一份值既给界面选，也被 AssistantPolicy 当白名单执行。
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelCatalog modelCatalog;

    public ModelController(ModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    /**
     * 获取模型列表
     */
    @GetMapping
    public ApiResponse<List<ModelCatalog.ModelInfo>> list() {
        return ApiResponse.success(modelCatalog.all());
    }
}
