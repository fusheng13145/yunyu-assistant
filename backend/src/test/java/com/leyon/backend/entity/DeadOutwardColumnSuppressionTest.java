package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体外发形状的全量静态门禁（v2.68 · C-135 立，v2.72 · C-141 翻转工具轨迹一半）。
 * <p>
 * 形状口径：对外 JSON 里出现的每一个键，都必须对应"产品里有地方往里写、且客户端能从这一列读到信息"的数据。
 * <ul>
 *   <li><b>逻辑删除列 {@code isDeleted}</b>——所有读接口都经 {@code @TableLogic} 过滤，外发值恒为 0，
 *       客户端拿到的是一个"永远不需要解释的常量"；它属服务端内部状态，与 {@code User.tokenVersion} 同类。</li>
 *   <li><b>工具轨迹三列 {@code Record.toolName/toolArgs/toolResult}</b>——v2.68 时全仓零写入点被撤了外发承诺；
 *       v2.72 工具回路在应用侧执行（S-24 收口）后三列有了真实写入点，判据随之翻转：
 *       有值必须外发（历史工具卡片靠它渲染），无值不得出键（{@code NON_NULL}，不回到"每条消息带三个 null"）。</li>
 * </ul>
 * 判据刻意取<b>行为</b>（Jackson 的真实输出）而非文本（"字段上有没有注解"），理由与
 * {@link EntityCredentialSuppressionTest} 相同：加在 getter 还是字段上效果不同，文本断言会把这种
 * 细节写成第二份要维护的猜测。抑制只在 JSON 层，<b>MyBatis 与归档 SQL 走的是 Java getter</b>，
 * 所以每个用例都反向断言 getter 仍返回真实值——否则"把列藏起来"会变成"把列写坏"。
 *
 * @author leyon
 */
class DeadOutwardColumnSuppressionTest {

    /** 逻辑删除列：按列名命中所有实体，新增带软删的实体自动进入本判据 */
    private static final String LOGICAL_DELETE_COLUMN = "isDeleted";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class LogicalDeleteColumn {

        @Test
        @DisplayName("任何实体的 JSON 输出都不含 isDeleted：软删标记是服务端内部状态")
        void serializedJson_neverCarriesIsDeleted() throws Exception {
            Set<Class<?>> checked = new LinkedHashSet<>();
            for (Class<?> entity : entityClasses()) {
                if (!hasDeclaredField(entity, LOGICAL_DELETE_COLUMN)) {
                    continue;
                }
                checked.add(entity);
                Object instance = entity.getDeclaredConstructor().newInstance();
                Field field = entity.getDeclaredField(LOGICAL_DELETE_COLUMN);
                field.setAccessible(true);
                field.set(instance, 0);

                String json = objectMapper.writeValueAsString(instance);

                assertThat(json)
                        .as("%s 把软删标记外发了", entity.getSimpleName())
                        .doesNotContain("\"isDeleted\"");
            }
            assertThat(checked).as("扫描不是空转：带软删列的实体一个都不能漏").hasSizeGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("抑制只发生在 JSON 层：Java getter 仍读到真实值，归档 SQL 依赖它")
        void suppressionDoesNotBreakJavaGetter() {
            User user = new User();
            user.setIsDeleted(User.NOT_DELETED);
            Record record = new Record();
            record.setIsDeleted(Record.NOT_DELETED);

            assertThat(user.getIsDeleted()).isEqualTo(User.NOT_DELETED);
            assertThat(record.getIsDeleted()).isEqualTo(Record.NOT_DELETED);
        }
    }

    @Nested
    class ToolTrajectoryColumns {

        @Test
        @DisplayName("工具轨迹三列有值必须外发：历史工具卡片靠它们渲染（v2.72 回路救活后的正向判据）")
        void toolColumnsAreSerializedWhenPresent() throws Exception {
            Record record = new Record();
            record.setRole(Record.ROLE_TOOL_CALL);
            record.setToolName("deep_research");
            record.setToolArgs("{\"query\":\"x\"}");

            String json = objectMapper.writeValueAsString(record);

            assertThat(json).contains("\"toolName\"")
                    .contains("\"toolArgs\"");
            // 反向锚点：getter 仍返回真实值，落库与归档不受外发形状影响
            assertThat(record.getToolName()).isEqualTo("deep_research");
            assertThat(record.getToolArgs()).isEqualTo("{\"query\":\"x\"}");
        }

        @Test
        @DisplayName("无工具行不得出键：NON_NULL 抑制在位，不回到 v2.68 之前'每条消息带三个 null'的形状")
        void plainRowsCarryNoToolKeys() throws Exception {
            Record record = new Record();
            record.setRole(Record.ROLE_ASSISTANT);
            record.setMessage("普通回答");

            String json = objectMapper.writeValueAsString(record);

            assertThat(json).doesNotContain("\"toolName\"")
                    .doesNotContain("\"toolArgs\"")
                    .doesNotContain("\"toolResult\"");
        }

        @Test
        @DisplayName("反向锚点：真正有值、且界面在用的键不能被顺手抑制")
        void realPayloadKeysStayVisible() throws Exception {
            Record record = new Record();
            record.setId("rec_probe");
            record.setRole(Record.ROLE_ASSISTANT);
            record.setMessage("回答正文");
            record.setCostTime(1200L);
            record.setSessionId("sess_probe");
            record.setAssistantId("assist_probe");
            record.setKnowledgebase(new Record.Knowledgebase(2, java.util.List.of("a.pdf"), false));

            String json = objectMapper.writeValueAsString(record);

            // 这三键是 ChatRobot/SmartRobot 历史回看真正读的，抑制它们＝界面丢消息
            assertThat(json).contains("\"id\"", "\"role\"", "\"message\"");
            // costTime / sessionId / assistantId 也必须在：配额读数与归属判定都看它们
            assertThat(json).contains("\"costTime\"", "\"sessionId\"", "\"assistantId\"");
            // knowledgebase 由 v2.41 折成对象外发，形状不能被本批改掉（原始 JSON 列名仍不外发）
            assertThat(json).contains("\"knowledgebase\"", "\"docCount\"")
                    .doesNotContain("\"knowledgebaseInfo\"");
        }
    }

    // ========== 扫描 ==========

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

    private static boolean hasDeclaredField(Class<?> entity, String name) {
        for (Field field : entity.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()
                    && field.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
