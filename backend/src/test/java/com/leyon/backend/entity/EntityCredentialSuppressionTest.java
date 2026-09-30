package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体层凭据外发的全量静态门禁（v2.64 · C-131，收口 v2.48 登记的"没有门禁防未来新凭据列"残余）
 * <p>
 * v2.48 把 {@code User.password} 的抑制从"每个出口手写 {@code setPassword(null)}"收到实体注解上，
 * 但当时只写了 {@code User} 一份口径，并在手册 6.6 留了一条残余：**新增凭据列时如果忘了写注解，
 * 没有任何东西会拦住它**。本类就是那个东西——它不点名某个实体，而是扫全部 {@code @TableName} 实体，
 * 凡字段名命中凭据词表就必须"序列化不出去"，因此 {@code totpSecret}／{@code externalAccessToken}
 * 这类未来列一加上、没加注解，这条判据当场红。
 * <p>
 * 判据形状刻意选**行为**（Jackson 的真实输出）而不是文本（"字段上有没有 {@code @JsonIgnore}"）：
 * v2.48 已经踩实"注解加在 getter 还是字段上效果不同"（Jackson 会把两者合并成同一逻辑属性），
 * 文本断言会把这种细节写成第二份需要维护的猜测。
 * <p>
 * 已知上限（手册 6.6 登记）：按名字判定，所以叫 {@code auth_blob} 的凭据列判不到；反过来也会误伤
 * {@code cacheKey} 这类无害命名——误伤的代价是改个名，漏判的代价是凭据外泄，所以宁可误伤。
 *
 * @author leyon
 */
class EntityCredentialSuppressionTest {

    /**
     * 凭据词表：按 **驼峰整词** 命中，不做子串匹配。
     * <p>
     * 整词是刻意的——子串会把 {@code Assistant.maxTokens}（成本参数，必须对外可见）判成凭据，
     * 而"把可见字段抑制掉"这种回归不会有任何报错，只会让界面少一个数字。
     */
    private static final Set<String> CREDENTIAL_WORDS = Set.of(
            "password", "pwd", "secret", "token", "key", "apikey", "hash",
            "salt", "signature", "credential", "authorization", "bearer", "cert", "otp");

    /** 哨兵值：出现在输出里就等价于"真实凭据出现在输出里" */
    private static final String SENTINEL = "SENTINEL-CREDENTIAL-VALUE";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("词表锚点：当前五个凭据字段全在命中集里，而成本参数 maxTokens 不算凭据")
    void vocabularyHitsKnownCredentialFields() {
        Set<String> hits = credentialFields();

        // 反向锚点：只验"命中了什么"验不出扫描本身在跑（空扫描也能让 containsAll 之外的断言通过）
        assertThat(hits).contains(
                "ApiApp.appKeyHash", "ApiApp.appKey", "ApiApp.webhookSecret",
                "User.password", "User.tokenVersion");
        // 刻意不把命中集写成"恰好这五个"：新增一个**已抑制**的凭据列不该逼人来改这条判据，
        // 那是登记表的做法，会把护栏训练成"红了就改断言"。上限交给下面两个 doesNotContain 守。
        assertThat(hits).doesNotContain("Assistant.maxTokens", "InviteCode.code");
    }

    @Nested
    class OutboundSuppression {

        @Test
        @DisplayName("任何实体的 JSON 输出都不含命中凭据词表的字段名与哨兵值")
        void serializedJson_neverCarriesCredentialFields() throws Exception {
            for (Class<?> entity : entityClasses()) {
                Object instance = populatedCredentialInstance(entity);
                String json = objectMapper.writeValueAsString(instance);
                Set<Field> fields = credentialFieldsOf(entity);
                if (fields.isEmpty()) {
                    continue;
                }
                // 哨兵只填凭据字段，所以它一旦出现在输出里就说明某个凭据值出去了
                assertThat(json).as("%s 的输出带了凭据字段的值", entity.getSimpleName())
                        .doesNotContain(SENTINEL);
                for (Field field : fields) {
                    assertThat(json)
                            .as("%s 的凭据字段 %s 出现在 JSON 键里", entity.getSimpleName(), field.getName())
                            .doesNotContain("\"" + field.getName() + "\":");
                }
            }
        }

        @Test
        @DisplayName("toString 同样不含哨兵：log.info(\"{}\", entity) 是第二条外发路径")
        void toString_neverPrintsCredentialFields() {
            for (Class<?> entity : entityClasses()) {
                Object instance = populatedCredentialInstance(entity);
                String text = instance.toString();

                assertThat(text)
                        .as("%s 的 toString 打印了凭据字段值", entity.getSimpleName())
                        .doesNotContain(SENTINEL);
            }
        }

        @Test
        @DisplayName("扫描不是空转：@TableName 类全部进来，且至少两个类各带凭据字段")
        void scanIsNotEmpty() {
            // 刻意不写"恰好 15 个"：新增实体应当自动进入判据，而不是先来改一条断言
            assertThat(entityClasses()).hasSizeGreaterThanOrEqualTo(15);
            Set<String> hits = credentialFields();
            assertThat(hits).hasSizeGreaterThanOrEqualTo(5);
            assertThat(hits).anyMatch(h -> h.startsWith("ApiApp."));
            assertThat(hits).anyMatch(h -> h.startsWith("User."));
        }
    }

    @Nested
    class OneTimeCredentialExits {

        /**
         * 抑制不能顺手切断"创建那一次给明文"这条唯一交付路径：Webhook 签名密钥只有拿到的那一次能用
         * （v2.17 漏了它，签名恒不生效；v2.19 补的就是这一次可见）。明文走显式构造的载荷，不走实体序列化。
         */
        @Test
        @DisplayName("实体不再承担一次性明文：appKey 与 webhookSecret 都抑制，创建响应由出口显式给")
        void oneTimePlaintextIsNotCarriedByEntitySerialization() throws Exception {
            ApiApp app = new ApiApp();
            app.setAppKey(SENTINEL);
            app.setWebhookSecret(SENTINEL);

            String json = objectMapper.writeValueAsString(app);

            assertThat(json).doesNotContain("appKey").doesNotContain(SENTINEL);
            assertThat(app.getAppKey()).isEqualTo(SENTINEL);
            assertThat(app.getWebhookSecret()).isEqualTo(SENTINEL);
        }
    }

    // ========== 扫描与构造 ==========

    /** 当前实体包内全部 {@code @TableName} 类（新增实体自动进入本判据，无需登记） */
    private static Set<Class<?>> entityClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(TableName.class));
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.leyon.backend.entity")) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("实体类无法加载: " + bd.getBeanClassName(), e);
            }
        }
        return classes;
    }

    /** 全仓凭据字段，形如 {@code ApiApp.webhookSecret} */
    private static Set<String> credentialFields() {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> entity : entityClasses()) {
            for (Field field : credentialFieldsOf(entity)) {
                names.add(entity.getSimpleName() + "." + field.getName());
            }
        }
        return names;
    }

    private static Set<Field> credentialFieldsOf(Class<?> entity) {
        Set<Field> fields = new LinkedHashSet<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            for (String word : splitCamelCase(field.getName())) {
                if (CREDENTIAL_WORDS.contains(word)) {
                    fields.add(field);
                    break;
                }
            }
        }
        return fields;
    }

    /** 驼峰拆词后转小写：{@code appKeyHash -> [app, key, hash]}，{@code maxTokens -> [max, tokens]} */
    private static Set<String> splitCamelCase(String name) {
        Set<String> words = new LinkedHashSet<>();
        String spaced = name
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        for (String part : spaced.split(" ")) {
            if (!part.isEmpty()) {
                words.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    /** 只给凭据字段填哨兵（其余字段留空，避免 LocalDateTime 触发 JavaTimeModule 依赖） */
    private static Object populatedCredentialInstance(Class<?> entity) {
        try {
            Object instance = entity.getDeclaredConstructor().newInstance();
            for (Field field : credentialFieldsOf(entity)) {
                field.setAccessible(true);
                if (field.getType() == String.class) {
                    field.set(instance, SENTINEL);
                } else if (field.getType() == Integer.class || field.getType() == int.class) {
                    field.set(instance, 7);
                }
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("实体 " + entity.getSimpleName() + " 无法构造", e);
        }
    }
}
