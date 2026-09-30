package com.leyon.backend.service;

import com.leyon.backend.entity.CallRecord;
import com.leyon.backend.entity.OrgMember;
import com.leyon.backend.mapper.UsageStatsMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用量统计口径服务（S-12 收口）
 *
 * <p>四条口径规则集中在本类，SQL 只负责取行：
 * ①只计已结算通话（正常结束 / 中断），失败、进行中、状态列 NULL 或未知值一律不计并在
 * {@code excludedCalls} 里点名；
 * ②消息数取自 {@code records}（user/assistant 两轮）而非 {@code call_records.message_count} 反列，
 * 并按已结算通话ID回查两侧消息表——两张表保留期独立，通话已归档而消息仍在活表时不能少计；
 * ③活表与归档表并读通话行，同 id 只计一次（活表优先），并入量以 {@code archivedCalls} 公开；
 * ④组织作用域按成员用户集合聚合——{@code call_records.org_id} 无写入路径，故不能按该列过滤。
 *
 * @author leyon
 */
@Service
public class UsageStatsService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UsageStatsMapper usageStatsMapper;
    private final OrgService orgService;

    public UsageStatsService(UsageStatsMapper usageStatsMapper, OrgService orgService) {
        this.usageStatsMapper = usageStatsMapper;
        this.orgService = orgService;
    }

    /**
     * 统计窗口内的用量
     *
     * @param userId 统计主体（来自认证属性，不接受请求参数指定本人）
     * @param range  day / week / month，其余值按 week
     * @param orgId  组织ID；为空则只统计本人，非空时先做成员校验（非成员 403）
     */
    public Map<String, Object> usage(String userId, String range, String orgId) {
        int windowDays = switch (range) {
            case "day" -> 1;
            case "month" -> 30;
            default -> 7;
        };
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusDays(windowDays - 1).atStartOfDay();

        Scope scope = resolveScope(userId, orgId);
        List<String> userIds = scope.userIds();

        List<SourcedCall> calls = mergeCalls(
                usageStatsMapper.selectLiveUsageCalls(userIds, since),
                usageStatsMapper.selectArchivedUsageCalls(userIds, since));

        long excludedFailed = 0;
        long excludedOngoing = 0;
        long excludedUndated = 0;
        long excludedUnknown = 0;
        long excludedOutside = 0;

        List<SourcedCall> settled = new ArrayList<>();
        for (SourcedCall sourced : calls) {
            Exclusion exclusion = classify(sourced.call().getStatus());
            if (exclusion != null) {
                switch (exclusion) {
                    case FAILED -> excludedFailed++;
                    case ONGOING -> excludedOngoing++;
                    case UNKNOWN -> excludedUnknown++;
                }
                continue;
            }
            if (sourced.call().getStartedAt() == null) {
                excludedUndated++;
                continue;
            }
            LocalDate date = sourced.call().getStartedAt().toLocalDate();
            if (date.isAfter(today)) {
                // 应用时钟与 DB 时钟有偏差时会出现窗口右界之外的行；计入总量却不计入任何一天会让
                // "逐日之和 == 总量" 这条对外不变式失效，故同样不计并点名
                excludedOutside++;
                continue;
            }
            settled.add(sourced);
        }

        Map<String, Long> messagesByCall = settled.isEmpty()
                ? Map.of()
                : mergeMessageCounts(
                        usageStatsMapper.countLiveMessagesByCall(callIds(settled)),
                        usageStatsMapper.countArchivedMessagesByCall(callIds(settled)));

        long callCount = settled.size();
        long totalDurationSec = 0;
        long totalMessageCount = 0;
        long archivedCalls = 0;
        Map<String, long[]> dayMap = new HashMap<>();

        for (SourcedCall sourced : settled) {
            CallRecord call = sourced.call();
            String key = call.getStartedAt().toLocalDate().format(DATE_FMT);
            long messageCount = messagesByCall.getOrDefault(call.getId(), 0L);
            long durationSec = call.getDurationSec() == null ? 0L : call.getDurationSec();
            long[] agg = dayMap.computeIfAbsent(key, k -> new long[3]);
            agg[0] += 1;
            agg[1] += durationSec;
            agg[2] += messageCount;

            totalDurationSec += durationSec;
            totalMessageCount += messageCount;
            if (sourced.archived()) {
                archivedCalls++;
            }
        }

        List<Map<String, Object>> dayList = new ArrayList<>(windowDays);
        for (int i = 0; i < windowDays; i++) {
            String date = today.minusDays(windowDays - 1 - i).format(DATE_FMT);
            long[] agg = dayMap.getOrDefault(date, new long[3]);
            Map<String, Object> item = new HashMap<>();
            item.put("date", date);
            item.put("callCount", agg[0]);
            item.put("durationSec", agg[1]);
            item.put("messageCount", agg[2]);
            dayList.add(item);
        }

        Map<String, Object> excluded = new HashMap<>();
        excluded.put("failed", excludedFailed);
        excluded.put("ongoing", excludedOngoing);
        excluded.put("undated", excludedUndated);
        excluded.put("unknown", excludedUnknown);
        excluded.put("outsideWindow", excludedOutside);

        Map<String, Object> result = new HashMap<>();
        result.put("range", range);
        result.put("since", since.toString());
        result.put("scope", scope.orgId() == null ? "self" : "org");
        if (scope.orgId() != null) {
            result.put("orgId", scope.orgId());
            result.put("memberCount", (long) userIds.size());
        }
        result.put("callCount", callCount);
        result.put("totalDurationSec", totalDurationSec);
        result.put("messageCount", totalMessageCount);
        result.put("archivedCalls", archivedCalls);
        result.put("excludedCalls", excluded);
        result.put("days", dayList);
        return result;
    }

    /**
     * 结算判定（口径唯一落点）：返回排除原因，{@code null} 表示该通话已结算、应计入
     */
    private Exclusion classify(Integer status) {
        if (status == null) {
            return Exclusion.UNKNOWN;
        }
        if (status == CallRecord.STATUS_FAILED) {
            return Exclusion.FAILED;
        }
        if (status == CallRecord.STATUS_IN_PROGRESS) {
            return Exclusion.ONGOING;
        }
        if (status == CallRecord.STATUS_ENDED || status == CallRecord.STATUS_INTERRUPTED) {
            return null;
        }
        return Exclusion.UNKNOWN;
    }

    private Scope resolveScope(String userId, String orgId) {
        if (!StringUtils.hasText(orgId)) {
            return new Scope(List.of(userId), null);
        }
        String target = orgId.trim();
        // 成员校验在 OrgService.listMembers 内（非成员抛 ForbiddenException ⇒ 403），故越权请求读不到任何数据
        List<String> memberUserIds = orgService.listMembers(target, userId).stream()
                .map(OrgMember::getUserId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return new Scope(memberUserIds, target);
    }

    /**
     * 并读两侧通话行，同 id 只认一次（活表优先：归档行是同一行的副本，重复计入会翻倍数字）
     */
    private List<SourcedCall> mergeCalls(List<CallRecord> live, List<CallRecord> archived) {
        List<SourcedCall> merged = new ArrayList<>(live.size() + archived.size());
        Set<String> liveIds = new HashSet<>();
        for (CallRecord call : live) {
            liveIds.add(call.getId());
            merged.add(new SourcedCall(call, false));
        }
        for (CallRecord call : archived) {
            if (call.getId() != null && liveIds.contains(call.getId())) {
                continue;
            }
            merged.add(new SourcedCall(call, true));
        }
        return merged;
    }

    /**
     * 按通话汇总消息数：同一侧同一通话的多行求和；两侧都有记录时以活表读数为准
     */
    private Map<String, Long> mergeMessageCounts(List<Map<String, Object>> live, List<Map<String, Object>> archived) {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : live) {
            String callId = callId(row);
            if (callId != null) {
                counts.merge(callId, count(row), Long::sum);
            }
        }
        for (Map<String, Object> row : archived) {
            String callId = callId(row);
            if (callId != null) {
                counts.putIfAbsent(callId, count(row));
            }
        }
        return counts;
    }

    /**
     * 已结算通话的ID（消息计数的入参：只问这些通话有多少轮对话，不再重复判归属与窗口）
     */
    private List<String> callIds(List<SourcedCall> settled) {
        List<String> ids = new ArrayList<>(settled.size());
        for (SourcedCall sourced : settled) {
            ids.add(sourced.call().getId());
        }
        return ids;
    }

    private String callId(Map<String, Object> row) {
        Object value = row.get("callId");
        return value == null ? null : String.valueOf(value);
    }

    private long count(Map<String, Object> row) {
        // COUNT() 的 JDBC 类型随驱动与表达式变化（Long / Integer / BigInteger），口径不能因类型漂移成 0
        Object value = row.get("cnt");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private record Scope(List<String> userIds, String orgId) {
    }

    private record SourcedCall(CallRecord call, boolean archived) {
    }

    private enum Exclusion {
        FAILED, ONGOING, UNKNOWN
    }
}
