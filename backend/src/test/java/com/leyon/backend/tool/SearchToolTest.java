package com.leyon.backend.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 联网搜索工具单元测试
 * 覆盖：入参校验、面向模型的文本拼装、结构化命中（含来源地址，供 deep_research 复用）、
 *       空响应 / 空结果 / 接口异常 / 解析异常四类降级文案
 *
 * @author leyon
 */
class SearchToolTest {

    private static final String ENDPOINT = "https://search.test/v1/search";

    private RestTemplate restTemplate;
    private SearchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        tool = new SearchTool(new ObjectMapper());
        setField("restTemplate", restTemplate);
        setField("apiKey", "k1");
        setField("endpoint", ENDPOINT);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = SearchTool.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tool, value);
    }

    private SearchTool.SearchRequest request(String query, Integer maxResults) {
        SearchTool.SearchRequest req = new SearchTool.SearchRequest();
        req.setQuery(query);
        req.setMaxResults(maxResults);
        return req;
    }

    @Test
    void blankQuery_rejectedWithoutRequest() {
        assertThat(tool.search(request("  ", null))).isEqualTo("搜索失败：搜索关键词不能为空");
        assertThat(tool.search(null)).isEqualTo("搜索失败：搜索关键词不能为空");
    }

    @Test
    void search_formatsTitleAndSnippetPerLine() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn(
                "{\"data\":{\"webPages\":{\"value\":["
                        + "{\"name\":\"标题一\",\"snippet\":\"摘要一\",\"url\":\"https://203.0.113.10/a\"},"
                        + "{\"name\":\"标题二\",\"snippet\":\"摘要二\",\"url\":\"https://203.0.113.11/b\"}"
                        + "]}}}}");

        assertThat(tool.search(request("关键词", 2))).isEqualTo("标题一: 摘要一\n标题二: 摘要二\n");
    }

    @Test
    void searchHits_returnsStructuredUrlForResearch() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn(
                "{\"data\":{\"webPages\":{\"value\":[{\"name\":\"标题一\",\"snippet\":\"摘要一\",\"url\":\"https://203.0.113.10/a\"}]}}}");

        SearchTool.SearchOutcome outcome = tool.searchHits("关键词", 3);
        assertThat(outcome.error()).isNull();
        assertThat(outcome.hits()).extracting(SearchTool.SearchHit::url)
                .containsExactly("https://203.0.113.10/a");
    }

    @Test
    void emptyResults_noticeWithoutError() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenReturn("{\"data\":{\"webPages\":{\"value\":[]}}}");

        assertThat(tool.search(request("关键词", null))).isEqualTo("未查询到相关内容");
        SearchTool.SearchOutcome outcome = tool.searchHits("关键词", null);
        assertThat(outcome.hits()).isEmpty();
        assertThat(outcome.error()).isNull();
    }

    @Test
    void nullResponse_and_nonArray_areReportedAsNotices() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn(null);
        assertThat(tool.search(request("关键词", null))).isEqualTo("搜索失败：接口返回数据为空");

        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn("{\"code\":1}");
        assertThat(tool.search(request("关键词", null))).isEqualTo("未查询到相关内容");
    }

    @Test
    void requestFailure_and_brokenJson_degradeToFixedText() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("boom"));
        assertThat(tool.search(request("关键词", null))).isEqualTo("搜索请求异常：接口调用失败");

        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn("not-json");
        assertThat(tool.search(request("关键词", null))).isEqualTo("搜索解析异常：结果解析失败");
    }

    @Test
    void missingUrl_yieldsEmptyUrlField() {
        when(restTemplate.postForObject(eq(ENDPOINT), any(), eq(String.class))).thenReturn(
                "{\"data\":{\"webPages\":{\"value\":[{\"name\":\"标题一\",\"snippet\":\"摘要一\"}]}}}");

        List<SearchTool.SearchHit> hits = tool.searchHits("关键词", 1).hits();
        assertThat(hits.get(0).url()).isEmpty();
        // 无地址时 search() 的展示文案不受影响
        assertThat(tool.search(request("关键词", 1))).isEqualTo("标题一: 摘要一\n");
    }
}
