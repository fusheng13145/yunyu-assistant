package com.leyon.backend.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体外发形状的全量静态门禁（v2.68 · C-135）
 * <p>
 * 形状口径：对外 JSON 里出现的每一个键，都必须对应"产品里有地方往里写、且客户端能从这一列读到信息"的数据。
 * 两类键不满足这个条件，本类把它们一起钉住：
 * <ul>
 *   <li><b>逻辑删除列 {@code isDeleted}</b>——所有读接口都经 {@code @TableLogic} 过滤，外发值恒为 0，
 *       客户端拿到的是一个"永远不需要解释的常量"；它属服务端内部状态，与 {@code User.tokenVersion} 同类。</li>
 *   <li><b>零写入路径列 {@code Record.toolName/toolArgs/toolResult}</b>——列与实体字段是给工具轨迹预留的，
 *       但全仓没有任何写入点（实测 {@code records} 与 {@code records_archive} 里这三列非空行数为 0），
 *       于是会话历史接口每条消息都带三个 {@code null}。前端曾经按这三列渲染"历史里的工具卡片"，
 *       那份渲染永远走不到——先撤外发承诺，功能本身登记为候选（手册 7.4）。</li>
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

    /**
     * 零写入路径列的显式清单（形如 {@code Record.toolName}）。
     * <p>
     * 这里必须是显式清单而不是"扫出来的"：判定"有没有写入路径"要同时看显式 setter 调用、
     * {@code @RequestBody} 反序列化（{@code Assistant} 整实体接单，voice/name/description 都从这儿写进来）、
     * 手写 SQL（{@code inviteCodeMapper.claimByCode} 直接更新 used_by/used_at）与库端默认值
     * （{@code org_members.joined_at} 是 CURRENT_TIMESTAMP）。本批实测里，纯 Java setter 扫描对
     * 这四种写入路径有四种误判——所以宁可登记清单，等真有工具轨迹落库时再把它从清单里摘掉。
     */
    private static final Set<String> NO_WRITER_COLUMNS = Set.of(
            "Record.toolName", "Record.toolArgs", "Record.toolResult");

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
    class NoWriterColumns {

        @Test
        @DisplayName("清单里的列序列化不出去，但 getter 仍返回真实值（落库与归档不受影响）")
        void noWriterColumnsAreNotSerialized() throws Exception {
            Record record = new Record();
            record.setToolName("deep_research");
            record.setToolArgs("{\"query\":\"x\"}");
            record.setToolResult("{\"ok\":true}");

            String json = objectMapper.writeValueAsString(record);

            for (String entry : NO_WRITER_COLUMNS) {
                String property = entry.substring(entry.indexOf('.') + 1);
                assertThat(json).as("%s 仍在对外载荷里", entry).doesNotContain("\"" + property + "\"");
            }
            assertThat(record.getToolName()).isEqualTo("deep_research");
            assertThat(record.getToolArgs()).isEqualTo("{\"query\":\"x\"}");
            assertThat(record.getToolResult()).isEqualTo("{\"ok\":true}");
        }

        @Test
        @DisplayName("反向锚点：清单之外真正有值、且界面在用的键不能被顺手抑制")
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

        @Test
        @DisplayName("清单不漂移：命中的实体字段恰好这些，多一个少一个都要重新表态")
        void registerMatchesCode() {
            Map<String, String> found = new LinkedHashMap<>();
            for (Class<?> entity : entityClasses()) {
                for (Field field : entity.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    String key = entity.getSimpleName() + "." + field.getName();
                    if (NO_WRITER_COLUMNS.contains(key)) {
                        found.put(key, field.getType().getSimpleName());
                    }
                }
            }
            assertThat(found.keySet()).containsExactlyInAnyOrderElementsOf(NO_WRITER_COLUMNS);
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
