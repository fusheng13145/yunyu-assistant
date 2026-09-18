package com.leyon.backend.controller;

import com.leyon.backend.common.ApiResponse;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.service.AssistantService;
import com.leyon.backend.service.CallRecordService;
import com.leyon.backend.service.RecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通话记录接口单元测试（v2.12：通话录音上传 / 回放）
 * 覆盖：上传成功/空文件/归属校验/记录不存在、下载成功/无录音/归属校验、详情含录音标志
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
class CallRecordControllerTest {

    private static final String USER = "user-1";
    private static final String OTHER = "user-2";
    private static final String CALL_ID = "call-1";

    @Mock
    private CallRecordService callRecordService;
    @Mock
    private AssistantService assistantService;
    @Mock
    private RecordService recordService;

    private CallRecordController controller;
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        controller = new CallRecordController(callRecordService, assistantService, recordService);
        tempDir = Files.createTempDirectory("call-rec-test");
        Field f = CallRecordController.class.getDeclaredField("recordingDir");
        f.setAccessible(true);
        f.set(controller, tempDir.toString());
    }

    private HttpServletRequest req(String userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lenient().when(request.getAttribute("userId")).thenReturn(userId);
        return request;
    }

    private CallRecord ownedRecord() {
        CallRecord record = new CallRecord();
        record.setId(CALL_ID);
        record.setUserId(USER);
        record.setAssistantId("assistant-1");
        record.setStatus(CallRecord.STATUS_ENDED);
        return record;
    }

    // ===================== 上传 =====================

    @Test
    void uploadRecording_success_persistsFileAndName() throws Exception {
        CallRecord record = ownedRecord();
        when(callRecordService.getById(CALL_ID)).thenReturn(record);
        MockMultipartFile file = new MockMultipartFile("file", "rec.webm", "audio/webm",
                "fake-webm-content".getBytes(StandardCharsets.UTF_8));

        ApiResponse<Void> result = controller.uploadRecording(CALL_ID, file, req(USER));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(record.getRecordingName()).isEqualTo(CALL_ID + ".webm");
        Path target = tempDir.resolve(CALL_ID + ".webm");
        assertThat(target).exists();
        assertThat(Files.readString(target)).isEqualTo("fake-webm-content");
    }

    @Test
    void uploadRecording_emptyFile_returnsParamError() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "", "audio/webm", new byte[0]);
        ApiResponse<Void> result = controller.uploadRecording(CALL_ID, empty, req(USER));
        assertThat(result.getCode()).isEqualTo(400);
    }

    @Test
    void uploadRecording_recordNotFound_returnsParamError() {
        when(callRecordService.getById(CALL_ID)).thenReturn(null);
        ApiResponse<Void> result = controller.uploadRecording(CALL_ID, new MockMultipartFile(
                "file", "a.webm", "audio/webm", new byte[]{1}), req(USER));
        assertThat(result.getCode()).isEqualTo(400);
    }

    @Test
    void uploadRecording_notOwner_returnsParamError() {
        when(callRecordService.getById(CALL_ID)).thenReturn(ownedRecord());
        ApiResponse<Void> result = controller.uploadRecording(CALL_ID, new MockMultipartFile(
                "file", "a.webm", "audio/webm", new byte[]{1}), req(OTHER));
        assertThat(result.getCode()).isEqualTo(400);
    }

    // ===================== 下载 =====================

    @Test
    void downloadRecording_success_returnsAudioStream() throws Exception {
        CallRecord record = ownedRecord();
        record.setRecordingName(CALL_ID + ".webm");
        Files.write(tempDir.resolve(CALL_ID + ".webm"), new byte[]{9, 8, 7});
        when(callRecordService.getById(CALL_ID)).thenReturn(record);

        ResponseEntity<Resource> response = controller.downloadRecording(CALL_ID, req(USER));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("audio/webm");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInputStream().readAllBytes()).containsExactly(9, 8, 7);
    }

    @Test
    void downloadRecording_noRecordingName_returnsNotFound() {
        when(callRecordService.getById(CALL_ID)).thenReturn(ownedRecord());
        ResponseEntity<Resource> response = controller.downloadRecording(CALL_ID, req(USER));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void downloadRecording_fileMissing_returnsNotFound() {
        CallRecord record = ownedRecord();
        record.setRecordingName("ghost.webm");
        when(callRecordService.getById(CALL_ID)).thenReturn(record);
        ResponseEntity<Resource> response = controller.downloadRecording(CALL_ID, req(USER));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void downloadRecording_notOwner_returnsForbidden() {
        when(callRecordService.getById(CALL_ID)).thenReturn(ownedRecord());
        ResponseEntity<Resource> response = controller.downloadRecording(CALL_ID, req(OTHER));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // ===================== 列表/详情含录音标志 =====================

    @Test
    @SuppressWarnings("unchecked")
    void list_marksRecordingFlag() {
        CallRecord withRec = ownedRecord();
        withRec.setRecordingName(CALL_ID + ".webm");
        CallRecord withoutRec = ownedRecord();
        withoutRec.setId("call-2");
        when(callRecordService.listByUser(USER, null, 0, 10)).thenReturn(List.of(withRec, withoutRec));
        when(callRecordService.countByUser(USER, null)).thenReturn(2L);
        when(assistantService.listByIds(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> result = controller.list(null, 1, 10, req(USER));

        assertThat(result.getCode()).isEqualTo(200);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result.getData().get("list");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("recording")).isEqualTo(true);
        assertThat(list.get(1).get("recording")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detail_includesRecordingFlag() {
        CallRecord record = ownedRecord();
        record.setRecordingName(CALL_ID + ".webm");
        when(callRecordService.getById(CALL_ID)).thenReturn(record);
        when(recordService.listByCallId(CALL_ID)).thenReturn(List.of());
        when(assistantService.listByIds(any())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> result = controller.detail(CALL_ID, req(USER));

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().get("recording")).isEqualTo(true);
    }
}
