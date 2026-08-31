package com.leyon.backend.service;

import java.util.List;

/**
 * 知识库检索提供者接口
 * 支持多种数据源的统一检索，解耦 ChatService 与具体知识库实现
 *
 * @author leyon
 */
public interface KnowledgeProvider {

    /**
     * 知识库检索命中结果
     *
     * @param context  拼接后的检索上下文文本
     * @param docCount 命中文档数量
     * @param docNames 命中文档名称列表
     */
    record KnowledgeHit(String context, int docCount, List<String> docNames) {
        public static KnowledgeHit empty() {
            return new KnowledgeHit("", 0, List.of());
        }
    }

    /**
     * 根据问题和数据集ID列表检索相关知识
     *
     * @param question   用户提问
     * @param datasetIds 数据集ID列表
     * @return 检索到的上下文文本，无结果返回空字符串
     */
    String queryKnowledgeBase(String question, List<String> datasetIds);

    /**
     * 检索知识库并返回结构化命中信息（上下文 + 命中文档名称/数量）
     * 供 query_end 消息携带 knowledgebase 引用信息使用
     *
     * @param question   用户提问
     * @param datasetIds 数据集ID列表
     * @return 结构化命中结果，无结果时返回 {@link KnowledgeHit#empty()}
     */
    default KnowledgeHit queryKnowledgeBaseWithDetail(String question, List<String> datasetIds) {
        return new KnowledgeHit(queryKnowledgeBase(question, datasetIds), 0, List.of());
    }

    /**
     * 获取提供者名称
     */
    String getProviderName();
}
