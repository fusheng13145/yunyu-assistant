package com.leyon.backend.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 开放平台拒绝与风险事件台账（v2.50 · 候选 ㉜）
 * <p>
 * 收口的是"拒绝发生了但没人知道"：{@code /api/open/**} 的 401/403/429 此前只有各自一条 WARN，
 * 既查不到"某把 Key 正在被人暴力试"，也查不到"某个集成把 Key 塞进了 URL"。逐条写库不可取
 * （这些请求正是量最大、最可能被刷的一类），故按 **事件类型 × 应用 × 小时桶** 聚合计数。
 * <p>
 * 基数刻意做成**调用方无法放大**的形态：应用维度只接受已存在应用的 id，认不出归属的凭据一律折进
 * {@link #UNKNOWN_APP} 一个格子里。所以总量上限＝事件种类 × (应用数 + 1) × 24，而不是"来一个随机
 * Key 就多一格"——后者会让这个台账本身变成 DoS 面。
 * <p>
 * <b>刻意不做持久化</b>：内存实现，重启清零、多实例各算各的份额。它的用途是"现在要不要处理这把 Key"
 * 的判据，不是取证；写进 7.4 的口径是"近 N 小时、单实例视角"。
 *
 * @author leyon
 */
@Component
public class OpenApiDenialMeter {

    /** 台账事件种类：前六项是"这次请求被拒"，{@code URL_KEY_USED} 是"被放行但属风险面" */
    public enum Kind {
        /** 请求根本没带凭据 */
        KEY_MISSING("未携带 API Key"),
        /** 带了但查不到、或应用已停用 */
        KEY_INVALID("API Key 无效或已停用"),
        /** 凭据有效但能力没开通（403） */
        SCOPE_DENIED("缺少所需能力"),
        /** 新增开放端点忘了登记所需能力，按拒绝处理（403） */
        ENDPOINT_UNREGISTERED("端点未登记所需能力"),
        /** 落在限流桶外（429） */
        RATE_LIMITED("请求超限"),
        /** 握手用 URL 查询参数带 Key 且开关已开：放行，但长期凭据已进中间件日志（v2.50 · C-110 的风险侧账） */
        URL_KEY_USED("以 URL 查询参数携带 API Key"),
        /** 握手用 URL 查询参数带 Key，但通道默认关闭 ⇒ 按未携带凭据拒 401（关闸后仍要知道有多少集成在用旧通道） */
        URL_KEY_REJECTED("URL 查询参数通道已关闸仍被使用");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** 给人看的中文名（前端直接显示，不再抄一份清单） */
        public String label() {
            return label;
        }
    }

    /** 无法归属到具体应用的行（无 Key / 无效 Key）折进这一格，避免调用方用随机 Key 撑爆台账 */
    public static final String UNKNOWN_APP = "unknown";

    /** 台账保留的小时桶数量（含当前桶）；读数窗口最大也只能取到这个数 */
    public static final int MAX_RETAINED_HOURS = 24;

    /** 全量清理的最小间隔，与写入同线程执行，避免为清理再起一个定时任务 */
    private static final long PRUNE_INTERVAL_MS = 60_000L;

    /** 键形如 {@code 种类|应用|小时序号}，值为该小时的累计次数 */
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    private final Clock clock;

    private volatile long lastPruneMs;

    public OpenApiDenialMeter() {
        this(Clock.systemUTC());
    }

    /** 测试注入固定时钟用（小时桶是纯算术，不需要真的等一小时） */
    OpenApiDenialMeter(Clock clock) {
        this.clock = clock;
        this.lastPruneMs = clock.millis();
    }

    /** 一条聚合结果 */
    public record Entry(Kind kind, String app, long count) {
    }

    /**
     * 记一次事件。
     *
     * @param kind 事件种类
     * @param appId 已识别的应用 id；认不出归属时传 null（折进 {@link #UNKNOWN_APP}）
     */
    public void record(Kind kind, String appId) {
        maybePrune();
        String key = kind.name() + '|' + (appId == null || appId.isBlank() ? UNKNOWN_APP : appId)
                + '|' + currentHourBucket();
        counters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /**
     * 取最近 {@code hours} 小时（含当前小时）的聚合行，按种类、应用排序。
     * 小时桶只是裁剪粒度（否则一次持续爆破会读出 24 行同样的东西），读数按「种类 × 应用」
     * 把窗口内的各桶求和。
     * 越界取值按 {@link #MAX_RETAINED_HOURS} 截断——读数窗口不可能长于台账本身保留的长度。
     */
    public List<Entry> recent(int hours) {
        int span = Math.min(Math.max(hours, 1), MAX_RETAINED_HOURS);
        long oldestBucket = currentHourBucket() - span + 1;
        Map<String, LongAdder> merged = new ConcurrentHashMap<>();
        for (Map.Entry<String, LongAdder> item : counters.entrySet()) {
            String[] parts = item.getKey().split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            if (Long.parseLong(parts[2]) < oldestBucket) {
                continue;
            }
            merged.computeIfAbsent(parts[0] + '|' + parts[1], k -> new LongAdder())
                    .add(item.getValue().sum());
        }
        List<Entry> entries = new ArrayList<>(merged.size());
        merged.forEach((key, adder) -> {
            int split = key.indexOf('|');
            entries.add(new Entry(Kind.valueOf(key.substring(0, split)), key.substring(split + 1), adder.sum()));
        });
        entries.sort((a, b) -> {
            int byKind = a.kind().compareTo(b.kind());
            return byKind != 0 ? byKind : a.app().compareTo(b.app());
        });
        return entries;
    }

    /** 当前整点所属的小时序号（用 UTC 纪元小时数，纯单调整数，便于按桶裁剪） */
    private long currentHourBucket() {
        return TimeUnit.MILLISECONDS.toHours(clock.millis());
    }

    private void maybePrune() {
        long now = clock.millis();
        if (now - lastPruneMs < PRUNE_INTERVAL_MS) {
            return;
        }
        lastPruneMs = now;
        long oldestKept = currentHourBucket() - (MAX_RETAINED_HOURS - 1);
        counters.keySet().removeIf(key -> {
            int split = key.lastIndexOf('|');
            if (split < 0) {
                return true;
            }
            try {
                return Long.parseLong(key.substring(split + 1)) < oldestKept;
            } catch (NumberFormatException e) {
                return true;
            }
        });
    }

    /**
     * 当前占用的格子数（测试接缝）：读窗口天然不返回过期桶，光看 {@link #recent(int)} 无法证明
     * 裁剪真的发生了，而"不涨"才是这个台账的存在理由
     */
    int cellCount() {
        return counters.size();
    }

    /** 供只读接口校验参数：窗口小时数的合法区间 */
    public static int clampWindow(int hours) {
        return Math.min(Math.max(hours, 1), MAX_RETAINED_HOURS);
    }
}
