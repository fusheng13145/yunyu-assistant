package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leyon.backend.entity.KnowledgeBase;
import com.leyon.backend.entity.Org;
import com.leyon.backend.mapper.KnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库本地授权登记表
 * 全仓只有一个写入点（RAGFlow 创建数据集成功后的回执同步），其余方法都是读侧判定；
 * 因为 /api/ragflow 用一把共享 Key 转发，"这个 dataset_id 属于谁"完全由这张表决定，
 * 所以任何能写入它的接口都等于交出全部知识库的读权限（v2.53 删除 /api/knowledges 的缘由，见手册 4.5）
 *
 * @author leyon
 */
@Service
public class KnowledgeBaseService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final OrgService orgService;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper, OrgService orgService) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.orgService = orgService;
    }

    /**
     * 登记一个知识库归属行
     * datasetId 必须来自 RAGFlow 的创建回执，不接受调用方直接指定（手册 4.5 第 13 条）
     *
     * @param kb 知识库实体
     * @return 保存后的知识库对象
     */
    public KnowledgeBase create(KnowledgeBase kb) {
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    /**
     * 上游数据集删除后注销本地归属声明（v2.81 · C-155，收口候选 ㊳）：
     * 此前上游删除只转发请求，本地行继续被 listVisibleDatasetIds 放行，
     * 表现为"检索时上游报错或空命中"而非越权，但死归属声明不可辨。
     * 逻辑删除（is_deleted=1）而非物理删，历史引用可追溯。
     */
    public int markLocalRowsDeletedByDatasetIds(java.util.List<String> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return 0;
        }
        return knowledgeBaseMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeBase>()
                .in(KnowledgeBase::getDatasetId, datasetIds));
    }

    /**
     * 上游元数据对账（v2.81 · C-156，收口候选 ㊶）：上游改名后本地行的 name/description 同步更新——
     * 此前本地行只在创建时同步一次、之后不可改，上游改名的漂移随使用累积。
     * 摘要条目：id/name/description；无变化的行不写（避免每次列表的写放大）。
     * 只做元数据同步、不做注销：上游缺失可能只是分页未覆盖，注销的语义归 deleteDataset 的立即注销。
     */
    public int reconcileLocalMetadata(java.util.List<KnowledgeBase> upstreamDatasets) {
        if (upstreamDatasets == null || upstreamDatasets.isEmpty()) {
            return 0;
        }
        java.util.Map<String, KnowledgeBase> upstream = new java.util.LinkedHashMap<>();
        for (KnowledgeBase ds : upstreamDatasets) {
            if (ds.getDatasetId() != null && !ds.getDatasetId().isBlank()) {
                upstream.put(ds.getDatasetId(), ds);
            }
        }
        int changed = 0;
        java.util.List<KnowledgeBase> localRows = knowledgeBaseMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeBase>()
                        .in(KnowledgeBase::getDatasetId, upstream.keySet()));
        for (KnowledgeBase local : localRows) {
            KnowledgeBase up = upstream.get(local.getDatasetId());
            String upstreamName = up.getName() == null || up.getName().isBlank() ? local.getName() : up.getName();
            String upstreamDesc = up.getDescription() == null ? local.getDescription() : up.getDescription();
            boolean nameChanged = !java.util.Objects.equals(local.getName(), upstreamName);
            boolean descChanged = !java.util.Objects.equals(local.getDescription(), upstreamDesc);
            if (nameChanged || descChanged) {
                KnowledgeBase patch = new KnowledgeBase();
                patch.setId(local.getId());
                patch.setName(upstreamName);
                patch.setDescription(upstreamDesc);
                knowledgeBaseMapper.updateById(patch);
                changed++;
            }
        }
        return changed;
    }

    /**
     * 校验指定数据集是否属于当前用户/组织（读取级授权，防越权）
     * 个人数据按 userId；组织数据要求当前用户为组织成员（viewer 以上可读）
     *
     * @param datasetId RAGFlow 数据集ID
     * @param userId    登录用户ID
     * @return 可读返回 true，否则 false
     */
    public boolean isOwnedDataset(String datasetId, String userId) {
        KnowledgeBase kb = findByDatasetId(datasetId);
        if (kb == null) {
            return false;
        }
        if (StringUtils.hasText(kb.getOrgId())) {
            return orgService.isMember(kb.getOrgId(), userId);
        }
        return StringUtils.hasText(userId) && userId.equals(kb.getUserId());
    }

    /**
     * 查询当前用户可见的数据集ID集合（个人知识库 + 所属组织的共享知识库）
     * 与 isOwnedDataset 同一套归属语义；仅在 RAGFlow 侧存在、本地无元数据的数据集不可见
     *
     * @param userId 登录用户ID
     * @return 可见的 dataset_id 集合
     */
    public Set<String> listVisibleDatasetIds(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Set.of();
        }
        List<String> orgIds = orgService.listMyOrgs(userId).stream().map(Org::getId).toList();
        LambdaQueryWrapper<KnowledgeBase> queryWrapper = new LambdaQueryWrapper<>();
        if (orgIds.isEmpty()) {
            queryWrapper.eq(KnowledgeBase::getUserId, userId);
        } else {
            queryWrapper.and(wrapper -> wrapper.eq(KnowledgeBase::getUserId, userId)
                    .or().in(KnowledgeBase::getOrgId, orgIds));
        }
        return knowledgeBaseMapper.selectList(queryWrapper).stream()
                .map(KnowledgeBase::getDatasetId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    /**
     * 收敛数据集ID列表：仅保留对当前用户可见的项（个人知识库 + 所属组织共享知识库）
     * 三条对话通道（文本 WS / 语音 WS / 开放 OpenAPI）装配 ChatService 前都必须经此单点，
     * 丢弃项在此记 WARN——把手写在调用方就会漏一条通道（㊷ 的成因，v2.58 收口）
     *
     * @param datasetIds 候选 RAGFlow 数据集ID，可为 null
     * @param userId     当前用户ID
     * @return 可见的数据集ID列表；全部不可见或未传时返回空列表（即本轮不检索知识库）
     */
    /**
     * 助手保存侧的可见性校验（v2.79 · C-152，收口候选 ㊺）：knowledge_ids 的 JSON 串里若包含
     * 当前用户不可见（不存在/已删除/无权限）的数据集，返回点名报错文案；全部可见或空配置返回 null。
     * 判据复用 {@link #retainVisibleDatasetIds}（唯一求交单点）——写侧与读侧从此同源，
     * "库里挂着 3 个知识库、实际检索 0 个、界面无提示"的形状不再出现。
     */
    public String rejectInvisibleDatasetIds(String knowledgeIdsJson, String userId) {
        if (knowledgeIdsJson == null || knowledgeIdsJson.isBlank()) {
            return null;
        }
        List<String> requested;
        try {
            requested = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    knowledgeIdsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception e) {
            return "知识库配置不是合法的 ID 列表，请刷新后重试";
        }
        List<String> visible = retainVisibleDatasetIds(requested, userId);
        if (visible.size() >= requested.size()) {
            return null;
        }
        List<String> invisible = requested.stream().filter(id -> !visible.contains(id)).distinct().toList();
        return "以下知识库不可见或已删除：" + invisible + "，请刷新后重试";
    }

    public List<String> retainVisibleDatasetIds(List<String> datasetIds, String userId) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return List.of();
        }
        Set<String> visibleIds = listVisibleDatasetIds(userId);
        List<String> retained = datasetIds.stream().filter(visibleIds::contains).toList();
        if (retained.size() < datasetIds.size()) {
            logger.warn("用户:{} 请求的数据集 {} 个中有 {} 个不可见，已按可见范围收敛",
                    userId, datasetIds.size(), datasetIds.size() - retained.size());
        }
        return retained;
    }

    /**
     * 校验指定数据集是否可管理（写/删除/解析级授权）
     * 个人数据按 userId；组织数据要求当前用户为 editor(含)以上
     *
     * @param datasetId RAGFlow 数据集ID
     * @param userId    登录用户ID
     * @return 可管理返回 true，否则 false
     */
    public boolean canManageDataset(String datasetId, String userId) {
        KnowledgeBase kb = findByDatasetId(datasetId);
        if (kb == null) {
            return false;
        }
        if (StringUtils.hasText(kb.getOrgId())) {
            try {
                orgService.requireRole(kb.getOrgId(), userId, com.leyon.backend.entity.Org.ROLE_EDITOR);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return StringUtils.hasText(userId) && userId.equals(kb.getUserId());
    }

    private KnowledgeBase findByDatasetId(String datasetId) {
        if (!StringUtils.hasText(datasetId)) {
            return null;
        }
        LambdaQueryWrapper<KnowledgeBase> queryWrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDatasetId, datasetId)
                .last("LIMIT 1");
        return knowledgeBaseMapper.selectOne(queryWrapper);
    }
}