package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量配额服务（P2-10 用量配额与账单统计）
 * 配额粒度：优先组织级（用户有 org 时），无组织按用户级；无配置记录时用环境变量默认值兜底
 * 拦截维度：助手上限、单日通话次数、单日通话时长、单日消息量
 *
 * @author leyon
 */
@Service
public class QuotaService {

    private final QuotaMapper quotaMapper;
    private final OrgService orgService;
    private final AssistantMapper assistantMapper;
    private final CallRecordMapper callRecordMapper;
    private final RecordMapper recordMapper;
    private final SessionMapper sessionMapper;

    @Value("${app.quota.assistant-limit:50}")
    private int defaultAssistantLimit;
    @Value("${app.quota.daily-call-limit:20}")
    private int defaultDailyCallLimit;
    @Value("${app.quota.daily-call-sec-limit:3600}")
    private long defaultDailyCallSecLimit;
    @Value("${app.quota.daily-msg-limit:500}")
    private int defaultDailyMsgLimit;

    public QuotaService(QuotaMapper quotaMapper, OrgService orgService,
                        AssistantMapper assistantMapper, CallRecordMapper callRecordMapper,
                        RecordMapper recordMapper, SessionMapper sessionMapper) {
        this.quotaMapper = quotaMapper;
        this.orgService = orgService;
        this.assistantMapper = assistantMapper;
        this.callRecordMapper = callRecordMapper;
        this.recordMapper = recordMapper;
        this.sessionMapper = sessionMapper;
    }

    /**
     * 获取用户生效的配额（org 优先 → user → 默认值拼装，不落库）
     */
    public Quota getEffective(String userId) {
        String orgId = orgService.getOrgIdOfUser(userId);
        if (StringUtils.hasText(orgId)) {
            Quota q = findQuota(Quota.SCOPE_ORG, orgId);
            if (q != null) {
                return q;
            }
            return buildDefault(Quota.SCOPE_ORG, orgId);
        }
        Quota q = findQuota(Quota.SCOPE_USER, userId);
        return q != null ? q : buildDefault(Quota.SCOPE_USER, userId);
    }

    private Quota findQuota(String scopeType, String scopeId) {
        return quotaMapper.selectOne(new LambdaQueryWrapper<Quota>()
                .eq(Quota::getScopeType, scopeType)
                .eq(Quota::getScopeId, scopeId)
                .last("LIMIT 1"));
    }

    private Quota buildDefault(String scopeType, String scopeId) {
        Quota q = new Quota();
        q.setScopeType(scopeType);
        q.setScopeId(scopeId);
        q.setAssistantLimit(defaultAssistantLimit);
        q.setDailyCallLimit(defaultDailyCallLimit);
        q.setDailyCallSecLimit(defaultDailyCallSecLimit);
        q.setDailyMsgLimit(defaultDailyMsgLimit);
        return q;
    }

    // ===================== 用量聚合 =====================

    /**
     * 聚合当前用量：{ quota:{...}, current:{...}, remaining:{...}, period, scopeType, scopeId }
     */
    public Map<String, Object> aggregateUsage(String userId) {
        Quota quota = getEffective(userId);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        long assistantCount = countAssistants(quota.getScopeType(), quota.getScopeId());
        long dailyCallCount = countCallsSince(quota.getScopeType(), quota.getScopeId(), todayStart);
        long dailyCallSec = sumCallSecSince(quota.getScopeType(), quota.getScopeId(), todayStart);
        long dailyMsgCount = countMessagesSince(quota.getScopeType(), quota.getScopeId(), todayStart);

        Map<String, Object> result = new HashMap<>();
        result.put("period", "daily");

        Map<String, Object> quotaMap = new HashMap<>();
        quotaMap.put("assistantLimit", quota.getAssistantLimit());
        quotaMap.put("dailyCallLimit", quota.getDailyCallLimit());
        quotaMap.put("dailyCallSecLimit", quota.getDailyCallSecLimit());
        quotaMap.put("dailyMsgLimit", quota.getDailyMsgLimit());
        result.put("quota", quotaMap);

        Map<String, Object> current = new HashMap<>();
        current.put("assistantCount", assistantCount);
        current.put("dailyCallCount", dailyCallCount);
        current.put("dailyCallSec", dailyCallSec);
        current.put("dailyMsgCount", dailyMsgCount);
        result.put("current", current);

        Map<String, Object> remaining = new HashMap<>();
        remaining.put("assistantRemaining", Math.max(0, quota.getAssistantLimit() - assistantCount));
        remaining.put("dailyCallRemaining", Math.max(0, quota.getDailyCallLimit() - dailyCallCount));
        remaining.put("dailyCallSecRemaining", Math.max(0, quota.getDailyCallSecLimit() - dailyCallSec));
        remaining.put("dailyMsgRemaining", Math.max(0, quota.getDailyMsgLimit() - dailyMsgCount));
        result.put("remaining", remaining);

        result.put("scopeType", quota.getScopeType());
        result.put("scopeId", quota.getScopeId());
        return result;
    }

    // ===================== 超限拦截 =====================

    /**
     * 创建助手前校验助力数量，超限抛 403
     */
    public void checkCreateAssistant(String userId) {
        Quota quota = getEffective(userId);
        long current = countAssistants(quota.getScopeType(), quota.getScopeId());
        if (current >= quota.getAssistantLimit()) {
            throw new QuotaExceededException("助手数量已达配额上限（" + quota.getAssistantLimit() + " 个），请删除多余助手或联系管理员调整配额");
        }
    }

    /**
     * 发起通话前校验单日通话次数/时长，超限抛 403
     */
    public void checkStartCall(String userId) {
        Quota quota = getEffective(userId);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long dailyCallCount = countCallsSince(quota.getScopeType(), quota.getScopeId(), todayStart);
        long dailyCallSec = sumCallSecSince(quota.getScopeType(), quota.getScopeId(), todayStart);
        if (dailyCallCount >= quota.getDailyCallLimit()) {
            throw new QuotaExceededException("单日通话次数已达上限（" + quota.getDailyCallLimit() + " 次），请明日再试");
        }
        if (dailyCallSec >= quota.getDailyCallSecLimit()) {
            throw new QuotaExceededException("单日通话时长已达上限（" + (quota.getDailyCallSecLimit() / 60) + " 分钟），请明日再试");
        }
    }

    /**
     * 发送消息前校验（文本对话场景调用），单日消息量超限抛 403
     */
    public void checkSendMessage(String userId) {
        Quota quota = getEffective(userId);
        long dailyMsgCount = countMessagesSince(quota.getScopeType(), quota.getScopeId(), LocalDate.now().atStartOfDay());
        if (dailyMsgCount >= quota.getDailyMsgLimit()) {
            throw new QuotaExceededException("单日消息量已达上限（" + quota.getDailyMsgLimit() + " 条），请明日再试");
        }
    }

    // ===================== 统计实现 =====================

    private long countAssistants(String scopeType, String scopeId) {
        LambdaQueryWrapper<Assistant> wrapper = new LambdaQueryWrapper<>();
        if (Quota.SCOPE_ORG.equals(scopeType)) {
            wrapper.eq(Assistant::getOrgId, scopeId);
        } else {
            wrapper.eq(Assistant::getUserId, scopeId);
        }
        return assistantMapper.selectCount(wrapper);
    }

    private long countCallsSince(String scopeType, String scopeId, LocalDateTime since) {
        LambdaQueryWrapper<CallRecord> wrapper = new LambdaQueryWrapper<CallRecord>()
                .ge(CallRecord::getCreatedAt, since);
        applyScope(wrapper, scopeType, scopeId);
        return callRecordMapper.selectCount(wrapper);
    }

    private long sumCallSecSince(String scopeType, String scopeId, LocalDateTime since) {
        LambdaQueryWrapper<CallRecord> wrapper = new LambdaQueryWrapper<CallRecord>()
                .ge(CallRecord::getCreatedAt, since);
        applyScope(wrapper, scopeType, scopeId);
        List<CallRecord> records = callRecordMapper.selectList(wrapper);
        long total = 0;
        for (CallRecord record : records) {
            if (record.getDurationSec() != null) {
                total += record.getDurationSec();
            }
        }
        return total;
    }

    private long countMessagesSince(String scopeType, String scopeId, LocalDateTime since) {
        // 消息记录通过会话维度归集到组织/用户（records 无 user_id/org_id，经 sessions 关联）
        LambdaQueryWrapper<Session> sessionWrapper = new LambdaQueryWrapper<Session>();
        if (Quota.SCOPE_ORG.equals(scopeType)) {
            sessionWrapper.eq(Session::getOrgId, scopeId);
        } else {
            sessionWrapper.eq(Session::getUserId, scopeId);
        }
        List<Session> sessions = sessionMapper.selectList(sessionWrapper);
        if (sessions.isEmpty()) {
            return 0;
        }
        List<String> sessionIds = sessions.stream().map(Session::getId).distinct().toList();
        return recordMapper.selectCount(new LambdaQueryWrapper<Record>()
                .in(Record::getSessionId, sessionIds)
                .ge(Record::getCreatedAt, since));
    }

    private void applyScope(LambdaQueryWrapper<CallRecord> wrapper, String scopeType, String scopeId) {
        if (Quota.SCOPE_ORG.equals(scopeType)) {
            wrapper.eq(CallRecord::getOrgId, scopeId);
        } else {
            wrapper.eq(CallRecord::getUserId, scopeId);
        }
    }
}