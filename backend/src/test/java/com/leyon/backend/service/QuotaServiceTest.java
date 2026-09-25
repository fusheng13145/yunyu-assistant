package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Quota;
import com.leyon.backend.entity.QuotaDailyUsage;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.mapper.AssistantMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.QuotaDailyUsageMapper;
import com.leyon.backend.mapper.QuotaMapper;
import com.leyon.backend.mapper.RecordMapper;
import com.leyon.backend.mapper.SessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量配额服务单元测试（P2-10 用量配额与账单统计）
 * 覆盖：org 优先/user 兜底、超限拦截（助手/通话/消息）、聚合口径与默认值兜底
 *
 * @author leyon
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaServiceTest {

    @Mock
    private QuotaMapper quotaMapper;
    @Mock
    private QuotaDailyUsageMapper quotaDailyUsageMapper;
    @Mock
    private OrgService orgService;
    @Mock
    private AssistantMapper assistantMapper;
    @Mock
    private CallRecordMapper callRecordMapper;
    @Mock
    private RecordMapper recordMapper;
    @Mock
    private SessionMapper sessionMapper;

    private QuotaService quotaService;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Assistant.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CallRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Record.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Session.class);
        quotaService = new QuotaService(quotaMapper, quotaDailyUsageMapper, orgService,
                assistantMapper, callRecordMapper, recordMapper, sessionMapper);
        // 纯单测环境无 Spring 注入，手动注入默认配额值（与 application.yaml 约定一致）
        applyDefaults("defaultAssistantLimit", 50);
        applyDefaults("defaultDailyCallLimit", 20);
        applyDefaults("defaultDailyCallSecLimit", 3600L);
        applyDefaults("defaultDailyMsgLimit", 500);
        // 默认 mock：无成员记录、无配额配置、默认无数据
        lenient().when(orgService.getOrgIdOfUser(any())).thenReturn(null);
        lenient().when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        lenient().when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        lenient().when(callRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        lenient().when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        lenient().when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        lenient().when(sessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        // 原子扣减默认桩：0 行＝"当日已无余量"（各用例按需改 1 或走建行走路径）
        lenient().when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(0);
        lenient().when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    }

    private void applyDefaults(String field, Object value) throws Exception {
        java.lang.reflect.Field f = QuotaService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(quotaService, value);
    }

    private Quota quota(int assistantLimit, int callLimit, long callSec, int msgLimit) {
        Quota q = new Quota();
        q.setAssistantLimit(assistantLimit);
        q.setDailyCallLimit(callLimit);
        q.setDailyCallSecLimit(callSec);
        q.setDailyMsgLimit(msgLimit);
        return q;
    }

    @Test
    void getEffective_orgPriorityOverUser() {
        when(orgService.getOrgIdOfUser("u1")).thenReturn("org1");
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(5, 5, 600, 50));
        Quota effective = quotaService.getEffective("u1");
        assertThat(effective.getAssistantLimit()).isEqualTo(5);
    }

    @Test
    void getEffective_userFallbackWhenNoOrg() {
        when(orgService.getOrgIdOfUser("u1")).thenReturn(null);
        when(quotaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(quota(10, 10, 1200, 100));
        Quota effective = quotaService.getEffective("u1");
        assertThat(effective.getDailyCallLimit()).isEqualTo(10);
    }

    @Test
    void getDefaultQuota_exposesEnvFallbackWithoutScope() {
        Quota defaults = quotaService.getDefaultQuota();
        assertThat(defaults.getScopeType()).isNull();
        assertThat(defaults.getScopeId()).isNull();
        assertThat(defaults.getAssistantLimit()).isEqualTo(50);
        assertThat(defaults.getDailyCallLimit()).isEqualTo(20);
        assertThat(defaults.getDailyCallSecLimit()).isEqualTo(3600L);
        assertThat(defaults.getDailyMsgLimit()).isEqualTo(500);
    }

    @Test
    void checkCreateAssistant_exceededThrows() {
        when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(50L);
        assertThatThrownBy(() -> quotaService.checkCreateAssistant("u1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkCreateAssistant_withinLimitPasses() {
        when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(10L);
        quotaService.checkCreateAssistant("u1");
    }

    @Test
    void checkSendMessage_exceededThrows() {
        // v2.35 起消息上限由"计数比较"改为原子扣减：UPDATE 影响行数 0 ⇒ 已达上限
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> quotaService.checkSendMessage("u1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkStartCall_callCountExceededThrows() {
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> quotaService.checkStartCall("u1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkStartCall_withinDurationPasses() {
        CallRecord rec = new CallRecord();
        rec.setDurationSec(120);
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(1); // 原子扣减成功
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rec));
        // 120s < 默认 3600s：时长判定读的是当日已结算通话记录，不写扣减账本
        quotaService.checkStartCall("u1");
    }

    @Test
    void checkStartCall_durationExceededThrowsOnDurationMessage() {
        CallRecord rec = new CallRecord();
        rec.setDurationSec(3600);
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(1); // 次数额度充足，只让时长这一维越界
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rec));
        // 断言文案含"通话时长"：否则次数分支的拒绝也能让本例通过
        assertThatThrownBy(() -> quotaService.checkStartCall("u1"))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("通话时长");
    }

    @Test
    void consumeDaily_updateHitsQuota_succeedsWithoutRowCreation() {
        when(quotaDailyUsageMapper.updateDailyUsage(eq(Quota.SCOPE_USER), eq("u1"),
                eq(QuotaDailyUsage.METRIC_DAILY_MSG), any(LocalDate.class), eq(500))).thenReturn(1);

        assertThat(quotaService.consumeDaily(Quota.SCOPE_USER, "u1",
                QuotaDailyUsage.METRIC_DAILY_MSG, 500)).isTrue();
        // 扣减成功即有余量，不应再走建行分支
        verify(quotaDailyUsageMapper, never()).insert(any(QuotaDailyUsage.class));
    }

    @Test
    void consumeDaily_updateMissesWithExistingRow_isExhausted() {
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(0);
        when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        // 行已存在但 UPDATE 0 行 ⇒ used 已达 limit，且不得再试扣（会把 used 推过上限）
        assertThat(quotaService.consumeDaily(Quota.SCOPE_USER, "u1",
                QuotaDailyUsage.METRIC_DAILY_MSG, 500)).isFalse();
        verify(quotaDailyUsageMapper, times(1)).updateDailyUsage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void consumeDaily_createsRowThenRetriesUpdate() {
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt()))
                .thenReturn(0, 1);
        when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        assertThat(quotaService.consumeDaily(Quota.SCOPE_ORG, "org1",
                QuotaDailyUsage.METRIC_DAILY_CALL, 20)).isTrue();

        ArgumentCaptor<QuotaDailyUsage> captor = ArgumentCaptor.forClass(QuotaDailyUsage.class);
        verify(quotaDailyUsageMapper).insert(captor.capture());
        QuotaDailyUsage row = captor.getValue();
        assertThat(row.getScopeType()).isEqualTo(Quota.SCOPE_ORG);
        assertThat(row.getScopeId()).isEqualTo("org1");
        assertThat(row.getMetric()).isEqualTo(QuotaDailyUsage.METRIC_DAILY_CALL);
        assertThat(row.getUsageDate()).isEqualTo(LocalDate.now());
        assertThat(row.getUsed()).isEqualTo(0);
        // 建行后恰好重试一次扣减
        verify(quotaDailyUsageMapper, times(2)).updateDailyUsage(any(), any(), any(), any(), anyInt());
    }

    @Test
    void consumeDaily_concurrentInsertConflict_isFailSafe() {
        // 本进程判"无行"，但另一实例已抢先建行 ⇒ insert 撞唯一键；重试扣减再撞窗口 ⇒ 按上限内已占处理
        when(quotaDailyUsageMapper.updateDailyUsage(any(), any(), any(), any(), anyInt())).thenReturn(0);
        when(quotaDailyUsageMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(quotaDailyUsageMapper.insert(any(QuotaDailyUsage.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_usage_scope'"));

        assertThat(quotaService.consumeDaily(Quota.SCOPE_USER, "u1",
                QuotaDailyUsage.METRIC_DAILY_MSG, 500)).isFalse();
    }

    @Test
    void aggregateUsage_returnsQuotaCurrentRemaining() {
        when(assistantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(callRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        Session s = new Session();
        s.setId("s1");
        when(sessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(40L);

        Map<String, Object> usage = quotaService.aggregateUsage("u1");
        assertThat(usage.get("period")).isEqualTo("daily");
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) usage.get("current");
        assertThat(current.get("assistantCount")).isEqualTo(3L);
        assertThat(current.get("dailyCallCount")).isEqualTo(2L);
        assertThat(current.get("dailyMsgCount")).isEqualTo(40L);
        @SuppressWarnings("unchecked")
        Map<String, Object> remaining = (Map<String, Object>) usage.get("remaining");
        assertThat(remaining.get("assistantRemaining")).isEqualTo(47L); // 默认 50 - 3
    }
}