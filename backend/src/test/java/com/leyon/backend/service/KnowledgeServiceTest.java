package com.leyon.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.service.KnowledgeProvider.KnowledgeHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
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
 * 知识库检索服务的降级口径单元测试
 * 覆盖：外部故障与"检索无命中"必须可辨（v2.39 吞错显性化）
 *
 * <p>桩口径：RestTemplate 与 RAGFlow 配置均为反射注入，不发真实网络请求。
 *
 * @author leyon
 */
class KnowledgeServiceTest {

    private RestTemplate restTemplate;
    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() throws Exception {
        knowledgeService = new KnowledgeService(new ObjectMapper());
        restTemplate = mock(RestTemplate.class);
        setField("restTemplate", restTemplate);
        setField("apiKey", "stub-key");
        setField("endpoint", "http://ragflow.test");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = KnowledgeService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(knowledgeService, value);
    }

    private void respond(String body) {
        when(restTemplate.postForEntity(eq("http://ragflow.test/api/v1/retrieval"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Test
    void retrieval_networkFailureReturnsFailedHit() {
        when(restTemplate.postForEntity(eq("http://ragflow.test/api/v1/retrieval"), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        KnowledgeHit hit = knowledgeService.queryKnowledgeBaseWithDetail("问题", List.of("ds-1"));
        // 修前：网络故障返回 empty()，与"知识库没有相关内容"完全同形，调用方无从分辨
        assertThat(hit.failed()).isTrue();
        assertThat(hit.context()).isEmpty();
        assertThat(hit.docCount()).isZero();
    }

    @Test
    void retrieval_unparsableResponseReturnsFailedHit() {
        respond("<html>502 Bad Gateway</html>");

        assertThat(knowledgeService.queryKnowledgeBaseWithDetail("问题", List.of("ds-1")).failed()).isTrue();
    }

    @Test
    void retrieval_errorCodeInHttp200BodyReturnsFailedHit() {
        // RAGFlow 用 HTTP 200 + 业务 code 表达错误，只看 chunks 是否存在会把"密钥不对"当成"知识库没内容"
        respond("{\"code\":401,\"message\":\"Authentication failed\"}");

        assertThat(knowledgeService.queryKnowledgeBaseWithDetail("问题", List.of("ds-1")).failed()).isTrue();
    }

    @Test
    void retrieval_blankBodyReturnsFailedHit() {
        // 合法的"无命中"是 {"code":0,"data":{"chunks":[]}}；HTTP 200 空体属异常返回，不能算成"知识库没答案"
        respond("");

        assertThat(knowledgeService.queryKnowledgeBaseWithDetail("问题", List.of("ds-1")).failed()).isTrue();
    }

    @Test
    void retrieval_emptyChunksIsNotFailure() {
        respond("{\"code\":0,\"data\":{\"chunks\":[]}}");

        KnowledgeHit hit = knowledgeService.queryKnowledgeBaseWithDetail("问题", List.of("ds-1"));
        assertThat(hit.failed()).isFalse();
        assertThat(hit.context()).isEmpty();
    }

    @Test
    void retrieval_successfulHitIsNotFailureAndCollectsDocNames() {
        respond("{\"code\":0,\"data\":{\"chunks\":["
                + "{\"content\":\"退款走原路\",\"document_keyword\":\"售后手册.pdf\"},"
                + "{\"content\":\"工时三天\",\"document_keyword\":\"物流说明.pdf\"}]}}");

        KnowledgeHit hit = knowledgeService.queryKnowledgeBaseWithDetail("问题", List.of("ds-1"));
        assertThat(hit.failed()).isFalse();
        assertThat(hit.docCount()).isEqualTo(2);
        assertThat(hit.docNames()).containsExactly("售后手册.pdf", "物流说明.pdf");
    }

    @Test
    void retrieval_missingDatasetIdsShortCircuitsWithoutRequest() {
        // 空数据集列表属"未配置"，不是失败：必须与故障态区分，否则配置缺失会被当成 RAGFlow 挂了
        KnowledgeHit hit = knowledgeService.queryKnowledgeBaseWithDetail("问题", List.of());
        assertThat(hit.failed()).isFalse();
        assertThat(hit.context()).isEmpty();
    }
}
