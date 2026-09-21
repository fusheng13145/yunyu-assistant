package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.leyon.backend.entity.OutboundCall;
import com.leyon.backend.mapper.OutboundCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PSTN 外呼任务服务（P2-17 开放 OpenAPI 语音外呼）
 * 管理外呼任务生命周期：创建(PENDING) → 异步经网关发起(DIALING→ACTIVE/FAILED) → 回调更新(ACTIVE→COMPLETED/FAILED)
 *
 * @author leyon
 */
@Service
public class OutboundCallService {

    private static final Logger logger = LoggerFactory.getLogger(OutboundCallService.class);

    private final OutboundCallMapper outboundCallMapper;
    private final PstnGateway pstnGateway;

    public OutboundCallService(OutboundCallMapper outboundCallMapper, PstnGateway pstnGateway) {
        this.outboundCallMapper = outboundCallMapper;
        this.pstnGateway = pstnGateway;
    }

    /**
     * 创建外呼任务（状态 PENDING）
     *
     * @param userId      属主用户ID
     * @param orgId       所属组织ID（可空）
     * @param assistantId 助手ID
     * @param phoneNumber 被叫电话号码
     * @return 任务ID
     */
    public String create(String userId, String orgId, String assistantId, String phoneNumber) {
        OutboundCall call = new OutboundCall();
        call.setUserId(userId);
        call.setOrgId(orgId);
        call.setAssistantId(assistantId);
        call.setPhoneNumber(phoneNumber);
        call.setStatus(OutboundCall.STATUS_PENDING);
        call.setIsDeleted(OutboundCall.NOT_DELETED);
        outboundCallMapper.insert(call);
        return call.getId();
    }

    /**
     * 异步发起外呼（PENDING → DIALING；网关成功 → ACTIVE，失败 → FAILED）
     */
    @Async
    @Transactional
    public void initiateAsync(String taskId) {
        OutboundCall call = getById(taskId);
        if (call == null) {
            logger.warn("外呼任务不存在，taskId:{}", taskId);
            return;
        }
        updateStatus(taskId, OutboundCall.STATUS_DIALING, null);
        call.setStartedAt(LocalDateTime.now());
        outboundCallMapper.updateById(call);

        PstnGateway.PstnResult result = pstnGateway.initiate(call);
        if (result.success()) {
            updateStatus(taskId, OutboundCall.STATUS_ACTIVE, null);
            logger.info("PSTN 外呼已接入网关，taskId:{}", taskId);
        } else {
            updateStatus(taskId, OutboundCall.STATUS_FAILED, result.failReason());
            logger.warn("PSTN 外呼发起失败，taskId:{}，原因:{}", taskId, result.failReason());
        }
    }

    /**
     * 查询 DIALING 状态且 started_at 早于给定时间的任务（供超时自动扫描：滞留未接通置 FAILED）
     *
     * @param before 起始时间阈值（含早于此时间的任务）
     * @return 滞留 DIALING 任务列表
     */
    public List<OutboundCall> listDialingOlderThan(LocalDateTime before) {
        return outboundCallMapper.selectList(new LambdaQueryWrapper<OutboundCall>()
                .eq(OutboundCall::getStatus, OutboundCall.STATUS_DIALING)
                .lt(OutboundCall::getStartedAt, before)
                .orderByAsc(OutboundCall::getCreatedAt));
    }

    /**
     * 根据 ID 查询外呼任务
     */
    public OutboundCall getById(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        return outboundCallMapper.selectById(taskId);
    }

    /**
     * 更新外呼任务状态（终结状态 COMPLETED/FAILED 记录完成时间）
     */
    public boolean updateStatus(String taskId, String status, String failReason) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        LambdaUpdateWrapper<OutboundCall> wrapper = new LambdaUpdateWrapper<OutboundCall>()
                .eq(OutboundCall::getId, taskId)
                .set(OutboundCall::getStatus, status);
        if (StringUtils.hasText(failReason)) {
            wrapper.set(OutboundCall::getFailReason, failReason);
        }
        if (OutboundCall.STATUS_COMPLETED.equals(status) || OutboundCall.STATUS_FAILED.equals(status)) {
            wrapper.set(OutboundCall::getCompletedAt, LocalDateTime.now());
        }
        return outboundCallMapper.update(null, wrapper) > 0;
    }

    /**
     * 按 ID 查询并校验属主（供回调/查询防越权）
     */
    public OutboundCall getOwned(String taskId, String userId) {
        OutboundCall call = getById(taskId);
        if (call == null || !userId.equals(call.getUserId())) {
            return null;
        }
        return call;
    }
}