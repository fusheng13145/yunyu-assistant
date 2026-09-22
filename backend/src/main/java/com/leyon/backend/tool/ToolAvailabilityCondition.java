package com.leyon.backend.tool;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 工具启用条件实现
 * 读取 RequiresProperty 声明的配置项判定工具是否可用；开关型配置用 expected 指定匹配值
 *
 * <p>说明：条件求值发生在 Bean 定义解析阶段，日志由 ToolRegistry 启动时统一输出，
 * 此处不打日志以免污染容器启动输出。
 *
 * @author leyon
 */
public class ToolAvailabilityCondition implements Condition {

    private static final String ANNOTATION = RequiresProperty.class.getName();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ANNOTATION);
        if (attributes == null) {
            return true;
        }
        String[] keys = (String[]) attributes.get("value");
        String expected = (String) attributes.get("expected");
        Environment environment = context.getEnvironment();
        for (String key : keys) {
            String actual = environment.getProperty(key);
            if (!StringUtils.hasText(actual)) {
                return false;
            }
            if (StringUtils.hasText(expected) && !expected.equalsIgnoreCase(actual.trim())) {
                return false;
            }
        }
        return true;
    }
}
