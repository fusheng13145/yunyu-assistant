package com.leyon.backend.service;

import com.leyon.backend.entity.OutboundCall;

/**
 * PSTN 外呼网关接口（P2-17 开放 OpenAPI 语音外呼）
 * 可插拔：默认 HTTP 实现对接外部网关，网关能力/协议差异由实现隔离；
 * 网关发起结果同步返回（成功=已接入呼叫流程，失败=任务置 FAILED），
 * 呼叫后续状态（接通/完成）经 /api/open/callbacks/pstn 回调更新。
 *
 * @author leyon
 */
public interface PstnGateway {

    /**
     * 发起外呼
     *
     * @param call 外呼任务（含 taskId/assistantId/phoneNumber 等）
     * @return 发起结果（success=false 时 failReason 说明原因）
     */
    PstnResult initiate(OutboundCall call);

    /**
     * 外呼发起结果
     */
    record PstnResult(boolean success, String failReason) {
    }
}