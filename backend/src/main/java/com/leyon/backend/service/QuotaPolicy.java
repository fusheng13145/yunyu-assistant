package com.leyon.backend.service;

import com.leyon.backend.entity.Quota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 配额数值边界（唯一判据入口）
 *
 * 配额是运营侧唯一能直接改变真实开销的数值配置。v2.54 收口的候选 ㉔ 处理的是同一族的另一半
 * （"按条计量的配额被单条内容打穿"，即助手级模型/人设），本类收口<b>配额本身</b>：
 * 此前 {@code AdminController.upsertQuota} 只校验 scopeType/scopeId，四项数值全链透传，
 * 而 {@link QuotaService} 把 {@code limit <= 0} 解释成"直接拒绝"。两侧合起来的失效形态不是报错，
 * 而是"管理员手滑填了 0 或负数 ⇒ 该作用域用户被静默锁死，而界面提示明天会恢复"。
 *
 * <p>两种落点按"有没有可拒绝的请求方"分家（与 {@link AssistantPolicy} 同一口径）：
 * <ul>
 *   <li>{@link #validateForWrite(Quota)}：<b>写侧拒绝</b>，HTTP 通道能把原因与上下限讲清楚，
 *       且一次报全部越界项，让运维一趟改完；</li>
 *   <li>{@link #clampEnvDefaults(Quota)}：<b>环境变量兜底侧回落 + WARN</b>。这里拒绝会让一个
 *       {@code APP_QUOTA_*} 手误把整站挡在启动之外，回落至少留下点名原值的日志。</li>
 * </ul>
 * 库内已存在的行不做读侧钳制：写侧与兜底侧都收口后，本进程不再有能产生越界值的入口（与 v2.54
 * 对 ㉔"刻意不做存量回填"同一取舍）。
 *
 * @author leyon
 */
@Component
public class QuotaPolicy {

    private static final Logger log = LoggerFactory.getLogger(QuotaPolicy.class);

    /** 维度名同时是写侧报错与运行时拒绝的文案主干，两处必须是同一个词，否则运维读不出是同一件事 */
    public static final String DIM_ASSISTANT = "助手数量";
    public static final String DIM_CALL_COUNT = "单日通话次数";
    public static final String DIM_CALL_SEC = "单日通话时长";
    public static final String DIM_MSG = "单日消息量";

    /**
     * 助手上限。判据不是"业务上最多要几个助手"，而是防数量级手滑：默认 50，这里留 200 倍余量，
     * 而 1e9 这类"以为填了个不限制"的值进不了库（它同时也是一条每次建助手都要跑的 COUNT 的规模）。
     */
    public static final int MAX_ASSISTANT_LIMIT = 10_000;

    /** 单日通话次数上限：10000 次/日 已要求每 8.6 秒一通且每通都真占用网关，同防数量级手滑 */
    public static final int MAX_DAILY_CALL_LIMIT = 10_000;

    /** 单日通话时长上限（秒）：判据＝一天的总秒数，超过它不可能被用满，因此这条不是取舍而是无意义值 */
    public static final long MAX_DAILY_CALL_SEC_LIMIT = 24 * 60 * 60;

    /** 单日消息量上限：10 万条/日 ≈ 连续 24 小时每秒 1.16 条，同防数量级手滑 */
    public static final int MAX_DAILY_MSG_LIMIT = 100_000;

    /** 下界统一为 0：0 的语义是"关闭该维度"（既有口径，管理页与手册 2.11 一致），负数没有语义 */
    public static final long MIN_LIMIT = 0;

    /** 写侧提示：负数之所以要单独讲，是因为"0=关闭"和"填错了"只差一个减号 */
    public static final String DISABLED_HINT = "0 表示关闭该维度（不会自动恢复），留空表示不修改该维度";

    /**
     * 运行时（被拦用户看到的）拒绝文案：上限为 0 不是"用满了明天再来"，而是管理端关掉了这个能力，
     * 两者的用户动作完全不同（前者等、后者找管理员），所以这句话必须由边界入口给出。
     */
    public static String disabledMessage(String dimension) {
        return dimension + "已由管理端关闭（上限 0），不会自动恢复，如需使用请联系管理员调整配额";
    }

    /**
     * 写侧校验：null 维度按"不修改"跳过，越界项一次报全
     *
     * @throws IllegalArgumentException 任一项越界（此时不产生任何写库动作）
     */
    public void validateForWrite(Quota quota) {
        if (quota == null) {
            return;
        }
        List<String> problems = new ArrayList<>();
        check(DIM_ASSISTANT, "个", quota.getAssistantLimit(), MAX_ASSISTANT_LIMIT, problems);
        check(DIM_CALL_COUNT, "次", quota.getDailyCallLimit(), MAX_DAILY_CALL_LIMIT, problems);
        check(DIM_CALL_SEC, "秒", quota.getDailyCallSecLimit(), MAX_DAILY_CALL_SEC_LIMIT, problems);
        check(DIM_MSG, "条", quota.getDailyMsgLimit(), MAX_DAILY_MSG_LIMIT, problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", problems));
        }
    }

    /**
     * 环境变量兜底侧回落：就地钳到边界内并记 WARN（点名维度、原值与对应的 {@code APP_QUOTA_*}）
     */
    public Quota clampEnvDefaults(Quota quota) {
        if (quota == null) {
            return null;
        }
        if (quota.getAssistantLimit() != null) {
            quota.setAssistantLimit((int) clamp(DIM_ASSISTANT, "APP_QUOTA_ASSISTANT_LIMIT",
                    quota.getAssistantLimit(), MAX_ASSISTANT_LIMIT));
        }
        if (quota.getDailyCallLimit() != null) {
            quota.setDailyCallLimit((int) clamp(DIM_CALL_COUNT, "APP_QUOTA_DAILY_CALL_LIMIT",
                    quota.getDailyCallLimit(), MAX_DAILY_CALL_LIMIT));
        }
        if (quota.getDailyCallSecLimit() != null) {
            quota.setDailyCallSecLimit(clamp(DIM_CALL_SEC, "APP_QUOTA_DAILY_CALL_SEC_LIMIT",
                    quota.getDailyCallSecLimit(), MAX_DAILY_CALL_SEC_LIMIT));
        }
        if (quota.getDailyMsgLimit() != null) {
            quota.setDailyMsgLimit((int) clamp(DIM_MSG, "APP_QUOTA_DAILY_MSG_LIMIT",
                    quota.getDailyMsgLimit(), MAX_DAILY_MSG_LIMIT));
        }
        return quota;
    }

    private void check(String dimension, String unit, Number value, long max, List<String> problems) {
        if (value == null) {
            return;
        }
        long v = value.longValue();
        String label = dimension + "上限";
        if (v < MIN_LIMIT) {
            problems.add(label + "不能为负数（当前 " + v + "）；" + DISABLED_HINT);
        } else if (v > max) {
            problems.add(label + "不能超过 " + max + " " + unit + "（当前 " + v + "）");
        }
    }

    private long clamp(String dimension, String envName, long value, long max) {
        if (value < MIN_LIMIT) {
            log.warn("环境变量 {} 为 {}，配额维度「{}」按关闭（0）处理", envName, value, dimension);
            return MIN_LIMIT;
        }
        if (value > max) {
            log.warn("环境变量 {} 为 {}，超出可执行上界，配额维度「{}」按 {} 处理", envName, value, dimension, max);
            return max;
        }
        return value;
    }
}
