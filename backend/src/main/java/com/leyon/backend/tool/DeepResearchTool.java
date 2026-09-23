package com.leyon.backend.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

/**
 * 深度研究工具（检索 + 多源正文抓取，一次工具迭代内完成）
 *
 * <p>与"模型自己连着调 web_search → fetch_webpage"的差别在于：本服务每轮工具迭代都要重跑一次
 * 完整的流式模型调用（语音场景即一轮播报延迟），故把"检索并读多篇"压缩为单次工具调用，
 * 既省下迭代次数（全局上限 5 次），也让多篇正文在一次上下文注入里同时可见。
 *
 * <p>组合而非复制：检索走 {@link SearchTool#searchHits}，正文抓取走 {@link WebFetchTool#fetch}，
 * 因此 SSRF 校验、关闭重定向、文档类型白名单与字节上限全部沿用，本类不重复实现网络请求。
 *
 * <p>启用条件 = 检索配置 + 网页抓取开关同时就绪（缺任一则本工具不注册），默认部署下不出现。
 *
 * @author leyon
 */
@Component
@RequiresProperty({"app.search.api-key", "app.search.endpoint", "app.webfetch.enabled=true"})
public class DeepResearchTool {

    /** 抓取网页数硬上限（入参越界即钳制） */
    private static final int MAX_SOURCES_LIMIT = 5;

    /** 单篇正文注入摘要的最大字符数 */
    private static final int PER_SOURCE_CHARS = 500;

    /** 整体返回的最大字符数，留出余量低于 ChatService 的工具结果截断阈值（2000） */
    private static final int TOTAL_CHARS = 1900;

    /** 默认抓取网页数 */
    @Value("${app.research.max-sources}")
    private int defaultMaxSources;

    private final SearchTool searchTool;
    private final WebFetchTool webFetchTool;

    public DeepResearchTool(SearchTool searchTool, WebFetchTool webFetchTool) {
        this.searchTool = searchTool;
        this.webFetchTool = webFetchTool;
    }

    /**
     * 注册 AI 工具回调：深度研究
     *
     * @return 工具回调实例
     */
    @Bean
    ToolCallback deepResearchFunction() {
        return FunctionToolCallback
                .builder("deep_research", (Function<ResearchRequest, String>) this::research)
                .description("深度研究：检索互联网并抓取多篇网页正文，返回带来源地址的资料摘要，"
                        + "适合需要综合多个来源的回答（比 web_search 内容更全、耗时更长）。"
                        + "入参：query=研究问题，maxSources=抓取网页数（1-5，可不填）")
                .inputType(ResearchRequest.class)
                .build();
    }

    /**
     * 执行"检索 → 逐篇抓取 → 汇总摘要"
     *
     * @param request 研究请求参数
     * @return 多来源资料摘要 / 错误提示
     */
    public String research(ResearchRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            return "研究失败：问题不能为空";
        }
        String query = request.getQuery().trim();
        int sources = clampSources(request.getMaxSources() != null ? request.getMaxSources() : defaultMaxSources);

        // 多要两条候选：命中里可能有抓不了的地址（内网/非文本类型），留出替补余量
        SearchTool.SearchOutcome outcome = searchTool.searchHits(query, sources + 2);
        if (outcome.error() != null) {
            return outcome.error();
        }
        List<SearchTool.SearchHit> hits = outcome.hits();
        if (hits.isEmpty()) {
            return "未查询到相关内容";
        }

        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (SearchTool.SearchHit hit : hits) {
            if (used >= sources) {
                break;
            }
            used++;
            sb.append("[").append(used).append("] ")
                    .append(StringUtils.hasText(hit.title()) ? hit.title() : "无标题").append("\n");
            if (StringUtils.hasText(hit.url())) {
                sb.append("来源：").append(hit.url()).append("\n");
            }
            sb.append(bodyOf(hit)).append("\n\n");
        }
        if (used == 0) {
            return "未查询到可抓取的内容";
        }
        sb.append("（以上为摘要，需要更完整内容时用 fetch_webpage 读取对应来源地址）");
        return sb.length() > TOTAL_CHARS ? sb.substring(0, TOTAL_CHARS) + "…(内容过长，已截断)" : sb.toString();
    }

    /**
     * 单篇正文：抓取成功取正文前段，失败/无正文则回退搜索摘要（不让一整次研究因单源失败而空手而归）
     */
    private String bodyOf(SearchTool.SearchHit hit) {
        String body = null;
        if (StringUtils.hasText(hit.url())) {
            WebFetchTool.WebFetchRequest fetchRequest = new WebFetchTool.WebFetchRequest();
            fetchRequest.setUrl(hit.url());
            String fetched = webFetchTool.fetch(fetchRequest);
            if (fetched != null && !fetched.startsWith("抓取失败")) {
                body = fetched;
            }
        }
        if (!StringUtils.hasText(body)) {
            body = StringUtils.hasText(hit.snippet()) ? hit.snippet() : "（该来源无可用正文）";
        }
        return body.length() > PER_SOURCE_CHARS ? body.substring(0, PER_SOURCE_CHARS) + "…" : body;
    }

    /**
     * 抓取网页数钳制到 1..MAX_SOURCES_LIMIT（配置值越界同样收敛，不向模型暴露越界语义）
     */
    private int clampSources(int requested) {
        return Math.min(Math.max(requested, 1), MAX_SOURCES_LIMIT);
    }

    /**
     * 深度研究请求参数
     */
    public static class ResearchRequest {
        /** 研究问题 */
        private String query;
        /** 抓取网页数（可选，1-5） */
        private Integer maxSources;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public Integer getMaxSources() {
            return maxSources;
        }

        public void setMaxSources(Integer maxSources) {
            this.maxSources = maxSources;
        }
    }
}
