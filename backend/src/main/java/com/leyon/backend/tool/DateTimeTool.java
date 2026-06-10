package com.leyon.backend.tool;

import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class DateTimeTool {

    @Bean
    public ToolCallback getCurrentDatetimeFunction() {
        return FunctionToolCallback.builder("get_current_datetime", (Supplier<String>) this::getCurrentDatetime)
                .description("Get the current date and time")
                .build();
    }

    public String getCurrentDatetime() {
        return LocalDateTime.now().toString();
    }
}
