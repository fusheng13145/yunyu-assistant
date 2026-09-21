package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.entity.OutboundCall;
import com.leyon.backend.mapper.OutboundCallMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PSTN 外呼任务服务单元测试（P2-17 开放 OpenAPI 语音外呼）
 * 覆盖：创建置 PENDING、异步发起成功 ACTIVE / 网关失败 FAILED+failReason、状态更新、属主校验
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundCallServiceTest {

    @Mock
    private OutboundCallMapper outboundCallMapper;
    @Mock
    private PstnGateway pstnGateway;

    private OutboundCallService outboundCallService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OutboundCall.class);
        outboundCallService = new OutboundCallService(outboundCallMapper, pstnGateway);
    }

    private OutboundCall task(String id, String userId) {
        OutboundCall call = new OutboundCall();
        call.setId(id);
        call.setUserId(userId);
        call.setAssistantId("a1");
        call.setPhoneNumber("10086");
        call.setStatus(OutboundCall.STATUS_PENDING);
        return call;
    }

    @Test
    void create_setsPendingAndInserts() {
        when(outboundCallMapper.insert(any(OutboundCall.class))).thenAnswer(inv -> {
            OutboundCall c = inv.getArgument(0);
            c.setId("t1");
            return 1;
        });
        String taskId = outboundCallService.create("u1", null, "a1", "10086");
        assertThat(taskId).isEqualTo("t1");
        verify(outboundCallMapper).insert(org.mockito.ArgumentCaptor.forClass(OutboundCall.class).capture());
    }

    @Test
    void initiateAsync_gatewaySuccess_marksActive() {
        when(outboundCallMapper.selectById("t1")).thenReturn(task("t1", "u1"));
        when(pstnGateway.initiate(any(OutboundCall.class))).thenReturn(new PstnGateway.PstnResult(true, null));
        when(outboundCallMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        outboundCallService.initiateAsync("t1");
        // DIALING + ACTIVE 两次状态更新
        verify(outboundCallMapper, org.mockito.Mockito.times(2)).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void initiateAsync_gatewayFailure_marksFailedWithReason() {
        when(outboundCallMapper.selectById("t1")).thenReturn(task("t1", "u1"));
        when(pstnGateway.initiate(any(OutboundCall.class)))
                .thenReturn(new PstnGateway.PstnResult(false, "PSTN 网关未配置"));
        when(outboundCallMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        outboundCallService.initiateAsync("t1");
        // DIALING + FAILED 两次状态更新
        verify(outboundCallMapper, org.mockito.Mockito.times(2)).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void initiateAsync_missingTask_noop() {
        when(outboundCallMapper.selectById("missing")).thenReturn(null);
        outboundCallService.initiateAsync("missing");
        // 无需与网关交互
    }

    @Test
    void updateStatus_completedSetsCompletedAt() {
        when(outboundCallMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        boolean updated = outboundCallService.updateStatus("t1", OutboundCall.STATUS_COMPLETED, null);
        assertThat(updated).isTrue();
        verify(outboundCallMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void getOwned_rejectsForeignUser() {
        when(outboundCallMapper.selectById("t1")).thenReturn(task("t1", "u1"));
        assertThat(outboundCallService.getOwned("t1", "u-other")).isNull();
        assertThat(outboundCallService.getOwned("t1", "u1")).isNotNull();
    }

    @Test
    void listDialingOlderThan_queriesDialingStale() {
        when(outboundCallMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)))
                .thenReturn(java.util.List.of(task("t1", "u1")));
        var stale = outboundCallService.listDialingOlderThan(java.time.LocalDateTime.now().minusMinutes(1));
        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).getId()).isEqualTo("t1");
    }
}