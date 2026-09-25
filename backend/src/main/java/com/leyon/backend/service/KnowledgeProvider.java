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
     * @param failed   本轮检索是否**失败**；`false` 且 `docCount=0` 表示"知识库确实没有相关内容"，
     *                 两者在修前同形（都返回 {@link #empty()}），调用方无法判断回答是否缺少依据
     */
    record KnowledgeHit(String context, int docCount, List<String> docNames, boolean failed) {

        /** 兼容既有三参调用点：不带失败标记即视为"检索成功但无命中" */
        public KnowledgeHit(String context, int docCount, List<String> docNames) {
            this(context, docCount, docNames, false);
        }

        public static KnowledgeHit empty() {
            return new KnowledgeHit("", 0, List.of(), false);
        }

        /** 检索未得出结论是因为外部故障，而非知识库没有答案 */
        public static KnowledgeHit failure() {
            return new KnowledgeHit("", 0, List.of(), true);
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
