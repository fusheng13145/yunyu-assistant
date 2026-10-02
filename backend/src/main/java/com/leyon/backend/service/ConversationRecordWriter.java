package com.leyon.backend.service;

import com.leyon.backend.entity.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对话记录落库单点（三通道共用）
 *
 * @author leyon
 */
@Service
public class ConversationRecordWriter {

    private static final Logger logger = LoggerFactory.getLogger(ConversationRecordWriter.class);

    private final RecordService recordService;
    private final SessionService sessionService;

    public ConversationRecordWriter(RecordService recordService, SessionService sessionService) {
        this.recordService = recordService;
        this.sessionService = sessionService;
    }

    /**
     * @param saved  成功落库的条数
     * @param failed 落库失败的条数（失败不中断整批，剩下的消息不该跟着一起丢）
     */
    public record Result(int saved, int failed) {

        public static final Result NOTHING = new Result(0, 0);

        public boolean hasFailure() {
            return failed > 0;
        }
    }

    /**
     * 落库一批待存对话记录。
     *
     * @param pending     待落库记录（调用方负责取出后不再重复提交）
     * @param assistantId 助手ID
     * @param sessionId   业务会话ID，为空则不关联会话、也不生成标题（语音链路）
     * @param callId      通话记录ID，为空则不关联（文本链路）
     */
    public Result persist(List<Record> pending, String assistantId, String sessionId, String callId) {
        if (pending == null || pending.isEmpty()) {
            return Result.NOTHING;
        }
        int saved = 0;
        int failed = 0;
        String firstUserMessage = null;
        for (Record record : pending) {
            // 三类行必须留住：普通消息（message 有值）、工具轨迹行（message 空但 toolName 必有，S-19）、
            // 失败回合的空正文助手行（message 空但 failReason 必有，S-22）——只按 message 判空会把后两类整行丢掉
            if (!StringUtils.hasText(record.getMessage()) && !StringUtils.hasText(record.getToolName())
                    && !StringUtils.hasText(record.getFailReason())) {
                continue;
            }
            if (firstUserMessage == null && Record.ROLE_USER == record.getRole()) {
                firstUserMessage = record.getMessage();
            }
            record.setId(null);
            record.setAssistantId(assistantId);
            if (StringUtils.hasText(sessionId)) {
                record.setSessionId(sessionId);
            }
            if (StringUtils.hasText(callId)) {
                record.setCallId(callId);
            }
            record.setIsDeleted(Record.NOT_DELETED);
            try {
                recordService.add(record);
                saved++;
            } catch (Exception e) {
                failed++;
                logger.error("对话记录落库失败，助手ID:{}，会话ID:{}，role:{}", assistantId, sessionId, record.getRole(), e);
            }
        }
        if (saved > 0 && StringUtils.hasText(sessionId)) {
            try {
                sessionService.autoTitleIfNeeded(sessionId, firstUserMessage);
            } catch (Exception e) {
                logger.error("会话自动标题生成失败，会话ID:{}（记录已保存 {} 条，不影响历史）", sessionId, saved, e);
            }
        }
        return new Result(saved, failed);
    }
}
