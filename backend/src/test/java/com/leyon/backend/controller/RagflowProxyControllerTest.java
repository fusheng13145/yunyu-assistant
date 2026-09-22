package com.leyon.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.common.ForbiddenException;
import com.leyon.backend.service.KnowledgeBaseService;
import com.leyon.backend.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAGFlow 代理控制器单元测试（数据集对象级授权）
 * 覆盖：列表按可见集过滤（数组/分页两种响应形态）、RAGFlow 自身错误透传、结构异常 fail-closed、
 *       删除数据集的逐 ID 可管理校验、检索测试的可见集授权
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagflowProxyControllerTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private HttpServletRequest request;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RagflowProxyController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new RagflowProxyController(restTemplate, objectMapper, knowledgeBaseService, knowledgeService);
        inject("apiKey", "test-api-key");
        inject("endpoint", "http://ragflow.test");
        when(request.getAttribute("userId")).thenReturn("u1");
    }

    private void inject(String fieldName, String value) throws Exception {
        Field field = RagflowProxyController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private void stubRagflowResponse(String payload) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(payload));
    }

    @Test
    void listDatasets_filtersOutForeignDatasets() throws Exception {
        stubRagflowResponse("{\"code\":0,\"data\":[{\"id\":\"d1\",\"name\":\"我的\"},{\"id\":\"d2\",\"name\":\"他人\"}]}");
        when(knowledgeBaseService.listVisibleDatasetIds("u1")).thenReturn(Set.of("d1"));

        ResponseEntity<String> response = controller.listDatasets(1, 100, request);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).path("id").asText()).isEqualTo("d1");
    }

    @Test
    void listDatasets_pagedObjectIsFilteredAndTotalRewritten() throws Exception {
        stubRagflowResponse("{\"code\":0,\"data\":{\"items\":[{\"id\":\"d1\"},{\"id\":\"d2\"}],\"total\":2}}");
        when(knowledgeBaseService.listVisibleDatasetIds("u1")).thenReturn(Set.of("d1"));

        ResponseEntity<String> response = controller.listDatasets(1, 100, request);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("items").size()).isEqualTo(1);
        assertThat(data.path("total").asInt()).isEqualTo(1);
    }

    @Test
    void listDatasets_passesThroughRagflowError() throws Exception {
        stubRagflowResponse("{\"code\":102,\"message\":\"鉴权失败\"}");
        when(knowledgeBaseService.listVisibleDatasetIds("u1")).thenReturn(Set.of());

        ResponseEntity<String> response = controller.listDatasets(1, 100, request);

        assertThat(response.getBody()).contains("\"code\":102");
    }

    @Test
    void listDatasets_failsClosedOnUnknownShape() throws Exception {
        stubRagflowResponse("{\"code\":0,\"data\":{\"unexpected\":\"x\"}}");
        when(knowledgeBaseService.listVisibleDatasetIds("u1")).thenReturn(Set.of("d1"));

        ResponseEntity<String> response = controller.listDatasets(1, 100, request);

        assertThat(objectMapper.readTree(response.getBody()).path("data").size()).isZero();
    }

    @Test
    void deleteDataset_rejectsForeignDatasetInBatch() {
        when(knowledgeBaseService.canManageDataset("d1", "u1")).thenReturn(true);
        when(knowledgeBaseService.canManageDataset("d2", "u1")).thenReturn(false);

        ResponseEntity<String> response = controller.deleteDataset("{\"ids\":[\"d1\",\"d2\"]}", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void deleteDataset_forwardsOwnedBatch() {
        when(knowledgeBaseService.canManageDataset(anyString(), anyString())).thenReturn(true);
        stubRagflowResponse("{\"code\":0,\"data\":true}");

        ResponseEntity<String> response = controller.deleteDataset("{\"ids\":[\"d1\"]}", request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void deleteDataset_rejectsUnparsableBody() {
        ResponseEntity<String> response = controller.deleteDataset("{\"ids\":\"not-an-array\"}", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void retrievalTest_rejectsInvisibleDatasetWithoutRetrieving() {
        when(knowledgeBaseService.listVisibleDatasetIds("u1")).thenReturn(Set.of("d1"));

        assertThatThrownBy(() -> controller.retrievalTest(
                Map.of("question", "测试问题", "datasetIds", List.of("d1", "d2")), request))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void retrievalTest_retrievesVisibleDatasets() {
        when(knowledgeBaseService.listVisibleDatasetIds("u1")).thenReturn(Set.of("d1", "d2"));
        when(knowledgeService.testRetrieval("测试问题", List.of("d1"))).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> response = controller.retrievalTest(
                Map.of("question", "测试问题", "datasetIds", List.of("d1")), request);

        assertThat(response.getCode()).isEqualTo(200);
    }
}
