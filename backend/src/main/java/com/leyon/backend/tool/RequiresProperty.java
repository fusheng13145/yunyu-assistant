package com.leyon.backend.tool;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具启用条件
 * 标注在工具类（或 @Bean 方法）上：所依赖配置项未就绪时，整个工具类不进入 Spring 容器，
 * 从而也不会出现在 ToolRegistry 与 LLM 的可选工具列表中（避免"模型能调用、调用必失败"）
 *
 * @author leyon
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ToolAvailabilityCondition.class)
public @interface RequiresProperty {

    /**
     * 依赖的配置项（全部就绪才启用），两种写法可混用：
     * <ul>
     *   <li>{@code app.image.api-key} —— 配置存在且非空白即就绪</li>
     *   <li>{@code app.webfetch.enabled=true} —— 配置值须等于该值（忽略大小写，用于开关型配置）</li>
     * </ul>
     */
    String[] value();
}
