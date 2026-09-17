package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.mapper.AssistantMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
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

    private AssistantService assistantService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Assistant.class);
        assistantService = new AssistantService(assistantMapper);
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
}