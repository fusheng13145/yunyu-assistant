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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用量配额服务（P2-10 用量配额与账单统计；v2.35 起单日额度为原子扣减）
 * 配额粒度：优先组织级（用户有 org 时），无组织按用户级；无配置记录时用环境变量默认值兜底
 * 拦截维度：助手上限（计数）、单日通话次数（扣减）、单日通话时长（只读判定）、单日消息量（扣减）
 * 数值边界与"上限为 0＝关闭"的文案都在 {@link QuotaPolicy}（v2.57 · C-124），本类不再各写一份
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
    private final QuotaPolicy quotaPolicy;

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
                        RecordMapper recordMapper, SessionMapper sessionMapper,
                        QuotaPolicy quotaPolicy) {
        this.quotaMapper = quotaMapper;
        this.quotaDailyUsageMapper = quotaDailyUsageMapper;
        this.orgService = orgService;
        this.assistantMapper = assistantMapper;
        this.callRecordMapper = callRecordMapper;
        this.recordMapper = recordMapper;
        this.sessionMapper = sessionMapper;
        this.quotaPolicy = quotaPolicy;
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
        // 环境变量也是外部输入：越界的兜底值若原样透传，"没配配额的用户"会拿到一个库里根本写不进去的值
        return quotaPolicy.clampEnvDefaults(q);
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
        int limit = quota.getAssistantLimit();
        if (limit <= 0) {
            throw new QuotaExceededException(QuotaPolicy.disabledMessage(QuotaPolicy.DIM_ASSISTANT));
        }
        long current = countAssistants(quota.getScopeType(), quota.getScopeId());
        if (current >= limit) {
            throw new QuotaExceededException("助手数量已达配额上限（" + limit + " 个），请删除多余助手或联系管理员调整配额");
        }
    }

    /**
     * 发起通话前校验单日通话次数/时长，超限抛 403
     * <p>
     * 顺序固定为"先扣次数、再查时长"：时长由业务表汇总而来、并非原子值，
     * 若先查时长再扣次数，时长临界时会出现"放行了但次数已烧掉"的中间态；
     * 反序则次数被拒时时长尚未判定，最多少放一次，不透支。
     * 例外是"时长上限本身为 0"——那是纯配置判定、不看用量，故排在扣减之前（见方法内注释）。
     * 通话时长指标（daily_call_sec）不建扣减账本：判据读的是业务表汇总，通话中的复核走 {@link #checkOngoingCallSec}。
     */
    public void checkStartCall(String userId) {
        Quota quota = getEffective(userId);
        // 时长上限为 0 时先拒再扣：这项判定只看配置、不看用量，没有"非原子读数"的次序问题。
        // 反过来（沿用"先扣次数"）会让管理端关掉语音后每一次尝试都白烧一通通话次数。
        rejectIfCallSecDisabled(quota);
        consumeOrThrow(quota, QuotaDailyUsage.METRIC_DAILY_CALL, quota.getDailyCallLimit(),
                QuotaPolicy.DIM_CALL_COUNT, "次");
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long dailyCallSec = sumCallSecSince(quota.getScopeType(), quota.getScopeId(), todayStart);
        rejectIfCallSecOver(quota, dailyCallSec);
    }

    /**
     * 通话进行中复核单日通话时长（语音回合边界调用），超限抛 403
     * <p>
     * 与 {@link #checkStartCall} 的区别有两层，都不能省：
     * 一是这里<b>不扣通话次数</b>——回合每轮都会进来，沿用 checkStartCall 等于把"单日通话次数"当秒表烧掉；
     * 二是进行中的通话在 {@code call_records} 里 durationSec 恒为 0（时长只在挂断时结算），
     * 只靠发起前那一次判定的话，一整通超长通话可以整轮穿透日上限，所以必须把"本通今天已经活的秒数"补进用量。
     * 跨零点的通话只计今日那一段：昨夜的部分属于昨天的配额日。
     *
     * @param callId 本通通话记录ID；为空（记录创建失败的降级路径）时退化为"只判当日已结算量"
     */
    public void checkOngoingCallSec(String userId, String callId) {
        Quota quota = getEffective(userId);
        rejectIfCallSecDisabled(quota);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long settled = sumCallSecSince(quota.getScopeType(), quota.getScopeId(), todayStart);
        rejectIfCallSecOver(quota, settled + liveCallSecSince(callId, todayStart));
    }

    /**
     * 时长维度的"关闭"判据与文案出口（发起前排在扣减之前，通话中排在查量之前）
     */
    private void rejectIfCallSecDisabled(Quota quota) {
        if (quota.getDailyCallSecLimit() <= 0) {
            throw new QuotaExceededException(QuotaPolicy.disabledMessage(QuotaPolicy.DIM_CALL_SEC));
        }
    }

    /**
     * 时长维度的"用满"判据与文案出口：发起前判已结算量，通话中判已结算量＋本通已活秒数
     */
    private void rejectIfCallSecOver(Quota quota, long usedSec) {
        if (usedSec >= quota.getDailyCallSecLimit()) {
            throw new QuotaExceededException("单日通话时长已达上限（" + (quota.getDailyCallSecLimit() / 60) + " 分钟），请明日再试");
        }
    }

    /**
     * 发送消息前校验（文本对话场景调用），单日消息量超限抛 403
     */
    public void checkSendMessage(String userId) {
        Quota quota = getEffective(userId);
        consumeOrThrow(quota, QuotaDailyUsage.METRIC_DAILY_MSG, quota.getDailyMsgLimit(),
                QuotaPolicy.DIM_MSG, "条");
    }

    /**
     * 扣减一次单日额度：用满抛 403；上限为 0 时直接拒绝（不产生扣减，也不建当日行）。
     * 两种拒绝的用户动作不同——"用满"等明天，"被关闭"要找回管理员——所以文案必须分开。
     */
    private void consumeOrThrow(Quota quota, String metric, int limit, String dimension, String unit) {
        if (limit <= 0) {
            throw new QuotaExceededException(QuotaPolicy.disabledMessage(dimension));
        }
        if (!consumeDaily(quota.getScopeType(), quota.getScopeId(), metric, limit)) {
            throw new QuotaExceededException(dimension + "已达上限（" + limit + " " + unit + "），请明日再试");
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
    /**
     * 工具调用的日限次判据（v2.82 · C-158，收口候选 ⑩）：按"用户 × 工具名"分格计量，
     * 上限由环境变量默认值给出（管理端 quotas 表暂不管理该维度）。
     *
     * @param userId   发起对话的用户（开放通道为应用属主）
     * @param toolName 工具名（web_search / generate_image / …）
     * @param limit    当日该工具的调用上限（来自 ToolQuotaGuard 的环境变量）
     */
    public void checkToolCall(String userId, String toolName, int limit) {
        consumeOrThrow(getEffective(userId), "daily_tool:" + toolName, limit, "工具调用", "次");
    }

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

    /**
     * 本通通话今日已活的秒数（进行中的记录 durationSec 恒为 0，只能按开始时间推）
     */
    private long liveCallSecSince(String callId, LocalDateTime todayStart) {
        if (!StringUtils.hasText(callId)) {
            return 0;
        }
        CallRecord record = callRecordMapper.selectById(callId);
        if (record == null || record.getStartedAt() == null) {
            return 0;
        }
        LocalDateTime from = record.getStartedAt().isBefore(todayStart) ? todayStart : record.getStartedAt();
        return Math.max(0, Duration.between(from, LocalDateTime.now()).getSeconds());
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