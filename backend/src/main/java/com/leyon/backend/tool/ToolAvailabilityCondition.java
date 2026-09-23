package com.leyon.backend.tool;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 工具启用条件实现
 * 读取 RequiresProperty 声明的配置项判定工具是否可用；声明支持 "key"（非空白即就绪）与
 * "key=value"（值须相等，忽略大小写）两种写法
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
        Environment environment = context.getEnvironment();
        for (String declaration : (String[]) attributes.get("value")) {
            int sep = declaration.indexOf('=');
            String key = sep < 0 ? declaration : declaration.substring(0, sep);
            String actual = environment.getProperty(key.trim());
            if (!StringUtils.hasText(actual)) {
                return false;
            }
            if (sep >= 0 && !declaration.substring(sep + 1).trim().equalsIgnoreCase(actual.trim())) {
                return false;
            }
        }
        return true;
    }
}
