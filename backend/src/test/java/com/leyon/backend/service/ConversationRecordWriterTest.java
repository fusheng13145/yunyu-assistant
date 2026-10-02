package com.leyon.backend.service;

import com.leyon.backend.entity.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 对话落库单点单元测试
 * 覆盖：空批次不查库、空白消息跳过、会话与通话关联口径、单条失败不吞整批、标题只跟首轮
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationRecordWriterTest {

    @Mock
    private RecordService recordService;
    @Mock
    private SessionService sessionService;

    private ConversationRecordWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ConversationRecordWriter(recordService, sessionService);
    }

    private static Record record(int role, String message) {
        Record r = new Record();
        r.setRole(role);
        r.setMessage(message);
        return r;
    }

    @Test
    void persist_emptyOrNull_touchesNothing() {
        assertThat(writer.persist(List.of(), "a1", "s1", null)).isSameAs(ConversationRecordWriter.Result.NOTHING);
        assertThat(writer.persist(null, "a1", "s1", null)).isSameAs(ConversationRecordWriter.Result.NOTHING);
        verifyNoInteractions(recordService, sessionService);
    }

    @Test
    void persist_blankMessageRowsSkipped() {
        // 流式失败时助手消息可能是空串：空内容落库只会让历史里多出读不懂的空行
        ConversationRecordWriter.Result result =
                writer.persist(List.of(record(Record.ROLE_USER, ""), record(Record.ROLE_ASSISTANT, "  ")),
                        "a1", "s1", null);

        assertThat(result.saved()).isZero();
        assertThat(result.failed()).isZero();
        verify(recordService, never()).add(any());
        verify(sessionService, never()).autoTitleIfNeeded(anyString(), any());
    }

    @Test
    void persist_toolTrajectoryRowsSurviveBlankMessageFilter() {
        // 工具轨迹行（role 2/3）message 为空但 toolName 必有：按 message 单独判空会把轨迹整批丢掉（v2.72 · S-19）
        Record callRow = new Record();
        callRow.setRole(Record.ROLE_TOOL_CALL);
        callRow.setToolName("weather");
        Record resultRow = new Record();
        resultRow.setRole(Record.ROLE_TOOL_RESULT);
        resultRow.setToolName("weather");

        ConversationRecordWriter.Result result =
                writer.persist(List.of(record(Record.ROLE_USER, "问"), callRow, resultRow), "a1", "s1", null);

        assertThat(result.saved()).isEqualTo(3);
        verify(recordService, org.mockito.Mockito.times(3)).add(any(Record.class));
    }

    @Test
    void persist_failedEmptyAssistantRowSurvivesBlankMessageFilter() {
        // 失败回合的空正文助手行（message 空 + failReason 必有）：判空再吞掉它，S-22 就只收到一半（v2.73 真机取证抓到）
        Record failedRow = new Record();
        failedRow.setRole(Record.ROLE_ASSISTANT);
        failedRow.setFailReason("模型服务异常，本轮回复未完成");

        ConversationRecordWriter.Result result =
                writer.persist(List.of(record(Record.ROLE_USER, "问"), failedRow), "a1", "s1", null);

        assertThat(result.saved()).isEqualTo(2);
        verify(recordService, org.mockito.Mockito.times(2)).add(any(Record.class));
    }

    @Test
    void persist_textChannel_stampsSessionAndTitlesFromFirstUserMessage() {
        ConversationRecordWriter.Result result = writer.persist(
                List.of(record(Record.ROLE_USER, "第一个问题"), record(Record.ROLE_ASSISTANT, "回答")),
                "a1", "s1", null);

        assertThat(result.saved()).isEqualTo(2);
        ArgumentCaptor<Record> captor = ArgumentCaptor.forClass(Record.class);
        verify(recordService, org.mockito.Mockito.times(2)).add(captor.capture());
        // 主键交给 MyBatis-Plus 生成：内存里的临时记录带着旧 ID 复用会撞主键
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.getId()).isNull();
            assertThat(r.getAssistantId()).isEqualTo("a1");
            assertThat(r.getSessionId()).isEqualTo("s1");
            assertThat(r.getCallId()).isNull();
            assertThat(r.getIsDeleted()).isEqualTo(Record.NOT_DELETED);
        });
        verify(sessionService).autoTitleIfNeeded("s1", "第一个问题");
    }

    @Test
    void persist_voiceChannel_stampsCallAndNeverTitles() {
        // 语音链路 sessionId 传空：这通对话不属于任何文本会话，标题自然也不该由它生成
        ConversationRecordWriter.Result result = writer.persist(
                List.of(record(Record.ROLE_USER, "几点了"), record(Record.ROLE_ASSISTANT, "三点")),
                "a1", null, "c1");

        assertThat(result.saved()).isEqualTo(2);
        ArgumentCaptor<Record> captor = ArgumentCaptor.forClass(Record.class);
        verify(recordService, org.mockito.Mockito.times(2)).add(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.getSessionId()).isNull();
            assertThat(r.getCallId()).isEqualTo("c1");
        });
        verifyNoInteractions(sessionService);
    }

    @Test
    void persist_singleRowFailure_doesNotAbortBatch() {
        doThrow(new RuntimeException("records 写入失败")).when(recordService)
                .add(org.mockito.ArgumentMatchers.argThat(r -> Record.ROLE_USER == r.getRole()));

        ConversationRecordWriter.Result result = writer.persist(
                List.of(record(Record.ROLE_USER, "问"), record(Record.ROLE_ASSISTANT, "答")),
                "a1", "s1", null);

        // 剩下的消息不该跟着一起丢：失败条数要单独计数，调用方据此给用户出口
        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.hasFailure()).isTrue();
    }

    @Test
    void persist_titleSkippedWhenNothingSaved() {
        doThrow(new RuntimeException("全部写入失败")).when(recordService).add(any());

        ConversationRecordWriter.Result result = writer.persist(
                List.of(record(Record.ROLE_USER, "问")), "a1", "s1", null);

        // 一条都没落库就不建标题：否则会出现"会话有标题却没有历史"的空会话
        assertThat(result.saved()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        verify(sessionService, never()).autoTitleIfNeeded(anyString(), any());
    }

    @Test
    void persist_titleFailure_stillReportsSavedRows() {
        org.mockito.Mockito.doThrow(new RuntimeException("标题生成失败"))
                .when(sessionService).autoTitleIfNeeded(anyString(), any());

        ConversationRecordWriter.Result result = writer.persist(
                List.of(record(Record.ROLE_USER, "问"), record(Record.ROLE_ASSISTANT, "答")),
                "a1", "s1", null);

        // 标题是锦上添花：它失败不能把已成功的落库谎报成失败，否则用户会重发同一轮
        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.failed()).isZero();
    }
}
