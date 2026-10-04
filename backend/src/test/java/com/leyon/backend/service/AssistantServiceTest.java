package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.mapper.AssistantMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手服务单元测试
 * 覆盖：创建归属、按用户查询过滤、删除/更新的空参数防护、批量回填
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantServiceTest {

    @Mock
    private AssistantMapper assistantMapper;
    @Mock
    private QuotaService quotaService;

    private AssistantService assistantService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Assistant.class);
        // 用真实策略而非桩：这一层要验的正是"落库前值已被钳制"，桩会把被测行为本身桩掉
        assistantService = new AssistantService(assistantMapper, quotaService,
                new AssistantPolicy(new ModelCatalog()));
    }

    @Test
    void create_persistsWithOwner() {
        Assistant assistant = new Assistant();
        assistant.setName("测试助手");
        assistant.setUserId("u1");
        Assistant created = assistantService.create(assistant);
        assertThat(created).isSameAs(assistant);
        verify(assistantMapper).insert(assistant);
    }

    @Test
    void listByUserId_emptyIdReturnsEmpty() {
        assertThat(assistantService.listByUserId("")).isEmpty();
        assertThat(assistantService.listByUserId(null)).isEmpty();
    }

    @Test
    void listByUserId_buildsUserFilter() {
        when(assistantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertThat(assistantService.listByUserId("u1")).isEmpty();
        verify(assistantMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void getById_emptyIdReturnsNull() {
        assertThat(assistantService.getById("")).isNull();
        assertThat(assistantService.getById(null)).isNull();
    }

    @Test
    void getById_existingReturnsEntity() {
        Assistant a = new Assistant();
        a.setId("a1");
        when(assistantMapper.selectById("a1")).thenReturn(a);
        assertThat(assistantService.getById("a1")).isSameAs(a);
    }

    @Test
    void delete_emptyIdReturnsFalse() {
        assertThat(assistantService.delete("")).isFalse();
        assertThat(assistantService.delete(null)).isFalse();
    }

    @Test
    void update_missingIdReturnsFalse() {
        Assistant noId = new Assistant();
        assertThat(assistantService.update(noId)).isFalse();
        assertThat(assistantService.update(null)).isFalse();
    }

    @Test
    void update_withIdDelegates() {
        Assistant a = new Assistant();
        a.setId("a1");
        a.setName("新名字");
        when(assistantMapper.updateById(a)).thenReturn(1);
        assertThat(assistantService.update(a)).isTrue();
        verify(assistantMapper).updateById(a);
    }

    @Test
    void listByIds_emptyReturnsEmpty() {
        assertThat(assistantService.listByIds(null)).isEmpty();
        assertThat(assistantService.listByIds(List.of())).isEmpty();
    }

    @Test
    void pageByUser_emptyIdReturnsEmpty() {
        assertThat(assistantService.pageByUser("", null, 0, 10)).isEmpty();
        assertThat(assistantService.pageByUser(null, "kw", 0, 10)).isEmpty();
    }

    @Test
    void pageByUser_offsetsAndFiltersByOwner() {
        when(assistantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertThat(assistantService.pageByUser("u1", null, 10, 5)).isEmpty();
        verify(assistantMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void pageByUser_keywordPassedIntoWrapper() {
        when(assistantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertThat(assistantService.pageByUser("u1", " 编程 ", 0, 10)).isEmpty();
        verify(assistantMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void countByUser_delegatesWithUserFilter() {
        when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        assertThat(assistantService.countByUser("u1", null)).isEqualTo(3L);
        verify(assistantMapper).selectCount(any(LambdaQueryWrapper.class));
    }

    @Test
    void countByUser_emptyIdReturnsZero() {
        assertThat(assistantService.countByUser("", "kw")).isZero();
        assertThat(assistantService.countByUser(null, null)).isZero();
    }

    /**
     * v2.54：落库前钳制。这里测的是"库里不可能有超限值"，而不是 AssistantPolicy 的边界本身
     * （后者由 AssistantPolicyTest 直接断言），所以只取一条越界项验证服务真的调了策略。
     */
    @Nested
    class CostClampBeforeWrite {

        @Test
        void create_clampsOversizedValuesBeforeInsert() {
            Assistant a = new Assistant();
            a.setName("越界助手");
            a.setUserId("u1");
            a.setModelName("gpt-9-mega");
            a.setMaxTokens(999_999);
            assistantService.create(a);

            assertThat(a.getModelName()).isNull();
            assertThat(a.getMaxTokens()).isEqualTo(AssistantPolicy.MAX_OUTPUT_TOKENS);
            verify(assistantMapper).insert(a);
        }

        @Test
        void update_clampsPersonalityBeforeUpdate() {
            Assistant a = new Assistant();
            a.setId("a1");
            a.setPersonality("啊".repeat(AssistantPolicy.MAX_PERSONALITY_CHARS + 1));
            when(assistantMapper.updateById(a)).thenReturn(1);

            assertThat(assistantService.update(a)).isTrue();
            assertThat(a.getPersonality()).hasSize(AssistantPolicy.MAX_PERSONALITY_CHARS);
        }

        @Test
        void update_keepsUnsetModelFieldsNullSoPartialUpdateStillSkipsThem() {
            Assistant a = new Assistant();
            a.setId("a1");
            a.setName("只改名字");
            when(assistantMapper.updateById(a)).thenReturn(1);

            assertThat(assistantService.update(a)).isTrue();
            assertThat(a.getModelName()).isNull();
            assertThat(a.getTemperature()).isNull();
            assertThat(a.getMaxTokens()).isNull();
        }

        @Test
        void update_forwardsExplicitEmptyModelToTheMapper() {
            // 候选 ㊸ 的服务层一侧：MP 的 updateById 只写非 null 列，所以"选了默认模型"必须是 "" 送到 mapper；
            // 这一层被归成 null，SQL 里就没有 model_name 这一列，清空永远不会发生
            Assistant a = new Assistant();
            a.setId("a1");
            a.setModelName("");
            when(assistantMapper.updateById(a)).thenReturn(1);

            assertThat(assistantService.update(a)).isTrue();
            assertThat(a.getModelName()).isEmpty();
            verify(assistantMapper).updateById(a);
        }
    }


    // ===================== ㊿ 乐观锁（v2.79 · C-151） =====================

    @Test
    void assistantEntityCarriesVersionAnnotationForOptimisticLock() throws Exception {
        // 乐观锁的两个前提各有一道判据：本例锁实体注解，MybatisPlusConfig 的拦截器注册由
        // BackendApplicationTests#optimisticLockerInterceptorIsRegistered 钉住——任一缺失＝㊿ 静默失效
        assertThat(com.leyon.backend.entity.Assistant.class
                .getDeclaredField("version")
                .isAnnotationPresent(com.baomidou.mybatisplus.annotation.Version.class)).isTrue();
    }
}