package com.leyon.backend.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解
 * 标注在 Controller 方法上，由切面自动记录审计日志
 *
 * @author leyon
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /**
     * 操作动作（LOGIN / ASSISTANT_CREATE / ASSISTANT_UPDATE / ASSISTANT_DELETE / KB_CREATE / KB_DELETE 等）
     */
    String action();

    /**
     * 目标类型（可空）
     */
    String targetType() default "";
}
