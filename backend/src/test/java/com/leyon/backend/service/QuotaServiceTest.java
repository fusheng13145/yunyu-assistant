package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leyon.backend.common.QuotaExceededException;
import com.leyon.backend.entity.Assistant;
import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.Quota;
import com.leyon.backend.entity.Record;
import com.leyon.backend.entity.Session;
import com.leyon.backend.mapper.AssistantMapper;
import com.leyon.backend.mapper.CallRecordMapper;
import com.leyon.backend.mapper.QuotaMapper;
import com.leyon.backend.mapper.RecordMapper;
import com.leyon.backend.mapper.SessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
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
        quotaService = new QuotaService(quotaMapper, orgService, assistantMapper, callRecordMapper, recordMapper, sessionMapper);
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
    void checkStartCall_callCountExceededThrows() {
        when(callRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(20L);
        assertThatThrownBy(() -> quotaService.checkStartCall("u1"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void checkStartCall_withinDurationPasses() {
        CallRecord rec = new CallRecord();
        rec.setDurationSec(120);
        when(callRecordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(callRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(rec));
        // 120s < 默认 3600s 时长上限，不应抛
        quotaService.checkStartCall("u1");
    }

    @Test
    void checkSendMessage_exceededThrows() {
        Session s = new Session();
        s.setId("s1");
        when(sessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));
        when(recordMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(500L);
        assertThatThrownBy(() -> quotaService.checkSendMessage("u1"))
                .isInstanceOf(QuotaExceededException.class);
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