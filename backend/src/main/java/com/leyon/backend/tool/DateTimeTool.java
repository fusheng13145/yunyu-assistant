package com.leyon.backend.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * 日期时间工具函数
 * 供 Spring AI 外部工具调用，获取系统当前时间
 *
 * @author leyon
 */
@Component
public class DateTimeTool {

    /** 日期时间格式化器，统一输出格式 */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 注册 AI 工具回调：获取当前日期时间
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback getCurrentDatetimeFunction() {
        return FunctionToolCallback
                .builder("get_current_datetime", (Supplier<String>) this::getCurrentDatetime)
                .description("获取系统当前的日期和时间，格式：yyyy-MM-dd HH:mm:ss")
                .build();
    }

    /**
     * 获取当前格式化后的日期时间
     *
     * @return 标准格式时间字符串
     */
    public String getCurrentDatetime() {
        return LocalDateTime.now().format(FORMATTER);
    }
}