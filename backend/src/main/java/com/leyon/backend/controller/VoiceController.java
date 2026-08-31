package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 音色字典接口
 * 服务端下发音色列表（id/名称/性别/描述），前端渲染选择面板，不再硬编码
 * 音色代码沿用腾讯云 TTS（4 女 4 男）
 *
 * @author leyon
 */
@RestController
@RequestMapping("/api/voices")
public class VoiceController {

    /**
     * 音色信息
     *
     * @param id          音色代码（腾讯 TTS 音色）
     * @param name        音色名称
     * @param gender      性别：1 女 / 2 男
     * @param description 音色描述
     */
    public record VoiceInfo(String id, String name, int gender, String description) {}

    /** 预置 8 个音色（4 女 4 男） */
    private static final List<VoiceInfo> VOICES = List.of(
            new VoiceInfo("601005", "智瑜", 1, "温柔知性女声，适合客服与陪伴场景"),
            new VoiceInfo("101025", "智聆", 1, "清晰自然女声，适合新闻播报"),
            new VoiceInfo("502003", "智婉", 1, "亲切甜美女声，适合日常对话"),
            new VoiceInfo("502001", "智媱", 1, "活泼年轻女声，适合娱乐场景"),
            new VoiceInfo("601004", "智宇", 2, "沉稳大气男声，适合企业场景"),
            new VoiceInfo("501007", "智亮", 2, "阳光清晰男声，适合教学讲解"),
            new VoiceInfo("501006", "智勇", 2, "浑厚有力男声，适合播报场景"),
            new VoiceInfo("101056", "智诚", 2, "温和亲切男声，适合陪伴场景")
    );

    /**
     * 获取音色字典列表
     */
    @GetMapping
    public ApiResponse<List<VoiceInfo>> list() {
        return ApiResponse.success(VOICES);
    }
}
