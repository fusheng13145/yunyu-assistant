package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量配额服务（P2-10 用量配额与账单统计；v2.35 起单日额度为原子扣减）
 * 配额粒度：优先组织级（用户有 org 时），无组织按用户级；无配置记录时用环境变量默认值兜底
 * 拦截维度：助手上限（计数）、单日通话次数（扣减）、单日通话时长（只读判定）、单日消息量（扣减）
 *
 * @author leyon
 */
@Service
public class QuotaService {

    private final QuotaMapper quotaMapper;
    private final QuotaDailyUsageMapper quotaDailyUsageMapper;
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

    public QuotaService(QuotaMapper quotaMapper, QuotaDailyUsageMapper quotaDailyUsageMapper,
                        OrgService orgService,
                        AssistantMapper assistantMapper, CallRecordMapper callRecordMapper,
                        RecordMapper recordMapper, SessionMapper sessionMapper) {
        this.quotaMapper = quotaMapper;
        this.quotaDailyUsageMapper = quotaDailyUsageMapper;
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

    /**
     * 环境变量兜底配额（管理端展示用）：quotas 表无对应作用域记录时，所有用户实际生效的就是这份
     */
    public Quota getDefaultQuota() {
        return buildDefault(null, null);
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
     * <p>
     * 注意口径：这里从<b>业务表</b>数（call_records / records），与拦截判定用的
     * {@code quota_daily_usage} 扣减账本允许漂移（记录回滚/删除/归档后额度刻意不退还）。
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
     * 创建助手前校验助手数量，超限抛 403
     * <p>
     * 仍为计数比较（非扣减）：助手是可删除资源，并发窗口以已存行数收敛，
     * 超发上界＝并发创建数，与"删一个再建一个"的运营成本同量级，不值得建账本。
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
     * <p>
     * 顺序固定为"先扣次数、再查时长"：时长由业务表汇总而来、并非原子值，
     * 若先查时长再扣次数，时长临界时会出现"放行了但次数已烧掉"的中间态；
     * 反序则次数被拒时时长尚未判定，最多少放一次，不透支。
     * 通话时长指标（daily_call_sec）依赖通话结束后的真实秒数，属语音阶段（二阶段）范围，本批不建扣减账本。
     */
    public void checkStartCall(String userId) {
        Quota quota = getEffective(userId);
        consumeOrThrow(quota, QuotaDailyUsage.METRIC_DAILY_CALL, quota.getDailyCallLimit(),
                "单日通话次数已达上限（" + quota.getDailyCallLimit() + " 次），请明日再试");
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long dailyCallSec = sumCallSecSince(quota.getScopeType(), quota.getScopeId(), todayStart);
        if (dailyCallSec >= quota.getDailyCallSecLimit()) {
            throw new QuotaExceededException("单日通话时长已达上限（" + (quota.getDailyCallSecLimit() / 60) + " 分钟），请明日再试");
        }
    }

    /**
     * 发送消息前校验（文本对话场景调用），单日消息量超限抛 403
     */
    public void checkSendMessage(String userId) {
        Quota quota = getEffective(userId);
        consumeOrThrow(quota, QuotaDailyUsage.METRIC_DAILY_MSG, quota.getDailyMsgLimit(),
                "单日消息量已达上限（" + quota.getDailyMsgLimit() + " 条），请明日再试");
    }

    /** 扣减一次单日额度，用满即抛 403；上限为 0 时直接拒绝（不产生扣减，也不建当日行） */
    private void consumeOrThrow(Quota quota, String metric, int limit, String exhaustedMessage) {
        if (limit <= 0) {
            throw new QuotaExceededException(exhaustedMessage);
        }
        if (!consumeDaily(quota.getScopeType(), quota.getScopeId(), metric, limit)) {
            throw new QuotaExceededException(exhaustedMessage);
        }
    }

    /**
     * 原子扣减一次单日额度（v2.35 资金防线：检查与扣减在同一条 UPDATE 内，并发不超发）。
     * <p>
     * {@code updateDailyUsage} 返回 0 有两种不可区分的含义——"当日已无余量"与"当日行还不存在"，
     * 故判一次存在性：行已存在则按用满处理（<b>不得</b>再试扣，否则会把 used 推过上限）；
     * 无行则建 used=0 的当日行后重试扣减一次。建行本身不预扣，是为了把"建行"与"扣减"
     * 收敛到同一条带余额条件的 UPDATE 上——insert 撞唯一键（多实例/多线程同时首发）即视为
     * 他方已在限额内占用，本次按失败处理（fail-safe 方向：宁少放行，不透支）。
     *
     * @return true 表示额度已被本次调用占用
     */
    public boolean consumeDaily(String scopeType, String scopeId, String metric, int limit) {
        LocalDate today = LocalDate.now();
        if (quotaDailyUsageMapper.updateDailyUsage(scopeType, scopeId, metric, today, limit) > 0) {
            return true;
        }
        Long existing = quotaDailyUsageMapper.selectCount(new LambdaQueryWrapper<QuotaDailyUsage>()
                .eq(QuotaDailyUsage::getScopeType, scopeType)
                .eq(QuotaDailyUsage::getScopeId, scopeId)
                .eq(QuotaDailyUsage::getMetric, metric)
                .eq(QuotaDailyUsage::getUsageDate, today));
        if (existing != null && existing > 0) {
            return false;
        }
        QuotaDailyUsage row = new QuotaDailyUsage();
        row.setScopeType(scopeType);
        row.setScopeId(scopeId);
        row.setMetric(metric);
        row.setUsageDate(today);
        row.setUsed(0);
        try {
            quotaDailyUsageMapper.insert(row);
        } catch (DuplicateKeyException e) {
            return false;
        }
        return quotaDailyUsageMapper.updateDailyUsage(scopeType, scopeId, metric, today, limit) > 0;
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