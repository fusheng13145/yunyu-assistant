package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.entity.Org;
import com.leyon.backend.mapper.KnowledgeBaseMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库归属查询单元测试
 * 覆盖：数据集可见集 = 个人知识库 + 所属组织共享知识库，且过滤掉无 dataset_id 的脏数据
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private OrgService orgService;

    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeBase.class);
        knowledgeBaseService = new KnowledgeBaseService(knowledgeBaseMapper, orgService);
    }

    private KnowledgeBase kb(String datasetId, String userId, String orgId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setDatasetId(datasetId);
        kb.setUserId(userId);
        kb.setOrgId(orgId);
        return kb;
    }

    @Test
    void withoutUser_returnsEmpty() {
        assertThat(knowledgeBaseService.listVisibleDatasetIds(null)).isEmpty();
        assertThat(knowledgeBaseService.listVisibleDatasetIds("  ")).isEmpty();
    }

    @Test
    void personalOnly_queriesByUserId() {
        when(orgService.listMyOrgs("u1")).thenReturn(List.of());
        when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(kb("d1", "u1", null)));

        assertThat(knowledgeBaseService.listVisibleDatasetIds("u1")).containsExactly("d1");

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(knowledgeBaseMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id").doesNotContain("org_id");
    }

    @Test
    void withOrg_queriesPersonalAndOrgDatasets() {
        Org org = new Org();
        org.setId("o1");
        when(orgService.listMyOrgs("u1")).thenReturn(List.of(org));
        when(knowledgeBaseMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(kb("d1", "u1", null), kb("d2", "u2", "o1"), kb(null, "u1", null)));

        Set<String> visible = knowledgeBaseService.listVisibleDatasetIds("u1");

        assertThat(visible).containsExactlyInAnyOrder("d1", "d2");
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(knowledgeBaseMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id").contains("org_id");
    }

    /**
     * 对象级授权闸门（v2.53 补测）：/api/ragflow 用一把共享 Key 转发，能不能读/删某个数据集
     * 完全由这两个方法决定；v2.53 之前它们一条测试都没有，而同期被删掉的 /api/knowledges
     * 恰好能让人往判定依据的表里自己写一行（真机实测：植入一行后 403 变 200）。
     */
    @Nested
    class DatasetOwnershipGates {

        @Test
        void personalRow_readableOnlyByOwner() {
            when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(kb("d1", "u1", null));

            assertThat(knowledgeBaseService.isOwnedDataset("d1", "u1")).isTrue();
            assertThat(knowledgeBaseService.isOwnedDataset("d1", "u2")).isFalse();
            assertThat(knowledgeBaseService.isOwnedDataset("d1", "  ")).isFalse();
        }

        @Test
        void orgRow_readableByAnyMember() {
            when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(kb("d1", "u2", "o1"));
            when(orgService.isMember("o1", "u1")).thenReturn(true);

            assertThat(knowledgeBaseService.isOwnedDataset("d1", "u1")).isTrue();
            assertThat(knowledgeBaseService.isOwnedDataset("d1", "u9")).isFalse();
        }

        @Test
        void personalRow_manageableOnlyByOwner() {
            when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(kb("d1", "u1", null));

            assertThat(knowledgeBaseService.canManageDataset("d1", "u1")).isTrue();
            assertThat(knowledgeBaseService.canManageDataset("d1", "u2")).isFalse();
        }

        @Test
        void orgRow_manageableByEditorButNotViewer() {
            when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(kb("d1", "u2", "o1"));
            doNothing().when(orgService).requireRole("o1", "u1", Org.ROLE_EDITOR);
            doThrow(new ForbiddenException("角色不足")).when(orgService).requireRole("o1", "u9", Org.ROLE_EDITOR);

            assertThat(knowledgeBaseService.canManageDataset("d1", "u1")).isTrue();
            assertThat(knowledgeBaseService.canManageDataset("d1", "u9")).isFalse();
        }

        @Test
        void unregisteredDataset_deniesBothGates() {
            when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            assertThat(knowledgeBaseService.isOwnedDataset("d1", "u1")).isFalse();
            assertThat(knowledgeBaseService.canManageDataset("d1", "u1")).isFalse();
        }

        @Test
        void blankDatasetId_deniesWithoutQuerying() {
            assertThat(knowledgeBaseService.isOwnedDataset("", "u1")).isFalse();
            assertThat(knowledgeBaseService.canManageDataset(null, "u1")).isFalse();

            verify(knowledgeBaseMapper, never()).selectOne(any());
        }
    }
}
