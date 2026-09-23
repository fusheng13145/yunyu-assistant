package com.leyon.backend.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 深度研究工具单元测试
 * 覆盖：入参校验、检索失败透传、无命中、正文抓取成功/失败回退摘要、抓取数钳制、整体长度截断
 * 检索与抓取均用 mock（本类不发真实网络请求，网络策略由 SearchTool / WebFetchTool 各自测试覆盖）
 *
 * @author leyon
 */
class DeepResearchToolTest {

    private SearchTool searchTool;
    private WebFetchTool webFetchTool;
    private DeepResearchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        searchTool = mock(SearchTool.class);
        webFetchTool = mock(WebFetchTool.class);
        tool = new DeepResearchTool(searchTool, webFetchTool);
        setDefaultMaxSources(3);
    }

    private void setDefaultMaxSources(int value) throws Exception {
        Field f = DeepResearchTool.class.getDeclaredField("defaultMaxSources");
        f.setAccessible(true);
        f.setInt(tool, value);
    }

    private SearchTool.SearchHit hit(String title, String snippet, String url) {
        return new SearchTool.SearchHit(title, snippet, url);
    }

    private DeepResearchTool.ResearchRequest request(String query, Integer maxSources) {
        DeepResearchTool.ResearchRequest req = new DeepResearchTool.ResearchRequest();
        req.setQuery(query);
        req.setMaxSources(maxSources);
        return req;
    }

    @Test
    void blankQuery_rejected() {
        assertThat(tool.research(request("  ", null))).isEqualTo("研究失败：问题不能为空");
        assertThat(tool.research(null)).isEqualTo("研究失败：问题不能为空");
    }

    @Test
    void searchFailure_isPassedThrough() {
        when(searchTool.searchHits(eq("问题"), any()))
                .thenReturn(SearchTool.SearchOutcome.failure("搜索请求异常：接口调用失败"));
        assertThat(tool.research(request("问题", null))).isEqualTo("搜索请求异常：接口调用失败");
    }

    @Test
    void noHits_returnsNotice() {
        when(searchTool.searchHits(any(), any())).thenReturn(SearchTool.SearchOutcome.success(List.of()));
        assertThat(tool.research(request("问题", 2))).isEqualTo("未查询到相关内容");
    }

    @Test
    void usesFetchedBodyAndKeepsSourceAddress() {
        when(searchTool.searchHits(eq("AI 芯片"), any())).thenReturn(SearchTool.SearchOutcome.success(List.of(
                hit("标题一", "摘一", "https://203.0.113.10/a"),
                hit("标题二", "摘二", "https://203.0.113.11/b"))));
        when(webFetchTool.fetch(any())).thenReturn("正文内容");

        String result = tool.research(request("AI 芯片", 2));
        assertThat(result)
                .contains("[1] 标题一")
                .contains("来源：https://203.0.113.10/a")
                .contains("正文内容")
                .contains("[2] 标题二")
                .endsWith("（以上为摘要，需要更完整内容时用 fetch_webpage 读取对应来源地址）");
    }

    @Test
    void fetchFailure_fallsBackToSnippet() {
        when(searchTool.searchHits(any(), any())).thenReturn(SearchTool.SearchOutcome.success(List.of(
                hit("标题一", "这是搜索摘要", "https://203.0.113.10/a"))));
        when(webFetchTool.fetch(any())).thenReturn("抓取失败：不支持的文档类型（application/pdf），仅支持网页/纯文本/JSON");

        assertThat(tool.research(request("问题", 1))).contains("这是搜索摘要").doesNotContain("抓取失败");
    }

    @Test
    void longBody_isTrimmedPerSource() {
        when(searchTool.searchHits(any(), any())).thenReturn(SearchTool.SearchOutcome.success(List.of(
                hit("标题一", "摘要", "https://203.0.113.10/a"))));
        when(webFetchTool.fetch(any())).thenReturn("字".repeat(1200));

        String result = tool.research(request("问题", 1));
        assertThat(result).contains("字".repeat(500)).doesNotContain("字".repeat(501));
    }

    @Test
    void sourceCountIsClamped() {
        List<SearchTool.SearchHit> hits = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            hits.add(hit("标题" + i, "摘要" + i, "https://203.0.113.10/" + i));
        }
        when(searchTool.searchHits(any(), any())).thenReturn(SearchTool.SearchOutcome.success(hits));
        when(webFetchTool.fetch(any())).thenReturn("正文");

        // 入参越界钳到上限 5
        assertThat(tool.research(request("问题", 99))).contains("[5] 标题4").doesNotContain("[6]");
        // 入参为 0 收敛到 1
        assertThat(tool.research(request("问题", 0))).contains("[1] 标题0").doesNotContain("[2]");
    }

    @Test
    void defaultSourceCountComesFromConfig() throws Exception {
        setDefaultMaxSources(2);
        List<SearchTool.SearchHit> hits = List.of(
                hit("标题1", "摘要1", "https://203.0.113.10/a"),
                hit("标题2", "摘要2", "https://203.0.113.11/b"),
                hit("标题3", "摘要3", "https://203.0.113.12/c"));
        when(searchTool.searchHits(any(), any())).thenReturn(SearchTool.SearchOutcome.success(hits));
        when(webFetchTool.fetch(any())).thenReturn("正文");

        String result = tool.research(request("问题", null));
        assertThat(result).contains("[2] 标题2").doesNotContain("[3]");
        // 检索时多要两条候选，给抓取失败的来源留替补
        verify(searchTool).searchHits(eq("问题"), eq(4));
    }

    @Test
    void totalOutputIsCapped() {
        List<SearchTool.SearchHit> hits = List.of(
                hit("标题一", "摘要", "https://203.0.113.10/a"),
                hit("标题二", "摘要", "https://203.0.113.11/b"),
                hit("标题三", "摘要", "https://203.0.113.12/c"),
                hit("标题四", "摘要", "https://203.0.113.13/d"),
                hit("标题五", "摘要", "https://203.0.113.14/e"));
        when(searchTool.searchHits(any(), any())).thenReturn(SearchTool.SearchOutcome.success(hits));
        when(webFetchTool.fetch(any())).thenReturn("长".repeat(500));

        String result = tool.research(request("问题", 5));
        assertThat(result).hasSizeLessThanOrEqualTo(1900 + "…(内容过长，已截断)".length())
                .endsWith("…(内容过长，已截断)");
    }
}
