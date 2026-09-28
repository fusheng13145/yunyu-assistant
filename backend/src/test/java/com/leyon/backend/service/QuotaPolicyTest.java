package com.leyon.backend.service;

import com.leyon.backend.entity.Quota;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配额边界判定单元测试（v2.57 · C-124）
 * <p>
 * 锁的是"数值域只有一处定义、且写侧与兜底侧共用同一处"。两侧各有一条反向锚点：
 * 只写"越界被拒"，则"一律钳到默认"这种改法同样全绿；只写"兜底被钳"，则"合法配置也被改写"无人发现。
 */
class QuotaPolicyTest {

    private final QuotaPolicy policy = new QuotaPolicy();

    private Quota write(String scopeType, String scopeId,
                        Integer assistant, Integer call, Long callSec, Integer msg) {
        Quota q = new Quota();
        q.setScopeType(scopeType);
        q.setScopeId(scopeId);
        q.setAssistantLimit(assistant);
        q.setDailyCallLimit(call);
        q.setDailyCallSecLimit(callSec);
        q.setDailyMsgLimit(msg);
        return q;
    }

    private String rejectMessage(Integer assistant, Integer call, Long callSec, Integer msg) {
        try {
            policy.validateForWrite(write("user", "u-1", assistant, call, callSec, msg));
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        throw new AssertionError("预期越界被拒，实际通过");
    }

    // ===================== 合法域 =====================

    @Test
    void everyDimensionWithinDomainIsAcceptedUnchanged() {
        Quota q = write("user", "u-1", 7, 21, 1800L, 999);
        assertThatCode(() -> policy.validateForWrite(q)).doesNotThrowAnyException();
        // 校验入口不改写值：静默改写用户刚填的配置不可诊断（与 AssistantPolicy 写侧同一口径）
        assertThat(q.getAssistantLimit()).isEqualTo(7);
        assertThat(q.getDailyCallLimit()).isEqualTo(21);
        assertThat(q.getDailyCallSecLimit()).isEqualTo(1800L);
        assertThat(q.getDailyMsgLimit()).isEqualTo(999);
    }

    @Test
    void omittedDimensionsAreNotValidated() {
        // 局部更新语义：null＝不修改该维度，缺省项不得被当成"越界"拒掉
        assertThatCode(() -> policy.validateForWrite(write("org", "o-1", null, null, null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void zeroIsLegalBecauseItMeansDisabledAndIsNamedInHint() {
        // 0 是既有口径（管理页与手册 2.11 都写"填 0＝封禁该维度"），不是缺陷；负数才是无语义值
        assertThatCode(() -> policy.validateForWrite(write("user", "u-1", 0, 0, 0L, 0)))
                .doesNotThrowAnyException();
        assertThat(QuotaPolicy.DISABLED_HINT).contains("0").contains("关闭");
    }

    @Test
    void upperBoundsThemselvesAreInclusive() {
        assertThatCode(() -> policy.validateForWrite(write("user", "u-1",
                QuotaPolicy.MAX_ASSISTANT_LIMIT, QuotaPolicy.MAX_DAILY_CALL_LIMIT,
                QuotaPolicy.MAX_DAILY_CALL_SEC_LIMIT, QuotaPolicy.MAX_DAILY_MSG_LIMIT)))
                .doesNotThrowAnyException();
    }

    // ===================== 写侧拒绝 =====================

    @Test
    void negativeValueRejectedAndNamesDimensionAndZeroSemantics() {
        String msg = rejectMessage(-1, null, null, null);
        assertThat(msg).contains("助手数量上限").contains("-1").contains("0 表示关闭");
        assertThat(rejectMessage(null, -5, null, null)).contains("单日通话次数上限").contains("-5");
        assertThat(rejectMessage(null, null, -1L, null)).contains("单日通话时长上限").contains("-1");
        assertThat(rejectMessage(null, null, null, -500)).contains("单日消息量上限").contains("-500");
    }

    @Test
    void aboveBoundRejectedAndPrintsTheBound() {
        String msg = rejectMessage(QuotaPolicy.MAX_ASSISTANT_LIMIT + 1, null, null, null);
        assertThat(msg).contains("助手数量上限").contains(String.valueOf(QuotaPolicy.MAX_ASSISTANT_LIMIT))
                .contains(String.valueOf(QuotaPolicy.MAX_ASSISTANT_LIMIT + 1));
        assertThat(rejectMessage(null, QuotaPolicy.MAX_DAILY_CALL_LIMIT + 1, null, null))
                .contains("单日通话次数上限").contains(String.valueOf(QuotaPolicy.MAX_DAILY_CALL_LIMIT + 1));
        assertThat(rejectMessage(null, null, QuotaPolicy.MAX_DAILY_CALL_SEC_LIMIT + 1, null))
                .contains("单日通话时长上限").contains(String.valueOf(QuotaPolicy.MAX_DAILY_CALL_SEC_LIMIT));
        assertThat(rejectMessage(null, null, null, QuotaPolicy.MAX_DAILY_MSG_LIMIT + 1))
                .contains("单日消息量上限").contains(String.valueOf(QuotaPolicy.MAX_DAILY_MSG_LIMIT));
    }

    @Test
    void callSecondsBoundIsOneDaySoItIsNotAnArbitraryNumber() {
        // 这条把"86400 是判据不是魔法数"钉住：一天只有 86400 秒，超过它不可能被用满
        assertThat(QuotaPolicy.MAX_DAILY_CALL_SEC_LIMIT).isEqualTo(24 * 60 * 60);
    }

    @Test
    void allOffendingDimensionsReportedTogether() {
        // 运维一次填错多项时，逐条报错要让人一次改完；只报第一项会逼着反复提交
        String msg = rejectMessage(-1, QuotaPolicy.MAX_DAILY_CALL_LIMIT + 1, null, -3);
        assertThat(msg).contains("助手数量上限").contains("单日通话次数上限").contains("单日消息量上限");
    }

    // ===================== 兜底侧（读侧回落，不抛异常） =====================

    @Test
    void envDefaultsAboveBoundClampedNotRejected() {
        // 环境变量是启动期的外部输入：这里拒绝会让整站起不来，回落 + WARN 才是可诊断且不拦路的方向
        Quota clamped = policy.clampEnvDefaults(write(null, null,
                QuotaPolicy.MAX_ASSISTANT_LIMIT + 999, 20, 999_999L, 500));
        assertThat(clamped.getAssistantLimit()).isEqualTo(QuotaPolicy.MAX_ASSISTANT_LIMIT);
        assertThat(clamped.getDailyCallSecLimit()).isEqualTo(QuotaPolicy.MAX_DAILY_CALL_SEC_LIMIT);
        assertThat(clamped.getDailyCallLimit()).isEqualTo(20);
        assertThat(clamped.getDailyMsgLimit()).isEqualTo(500);
    }

    @Test
    void envDefaultsNegativeCollapseToDisabled() {
        Quota clamped = policy.clampEnvDefaults(write(null, null, -1, -2, -3L, -4));
        assertThat(clamped.getAssistantLimit()).isZero();
        assertThat(clamped.getDailyCallLimit()).isZero();
        assertThat(clamped.getDailyCallSecLimit()).isZero();
        assertThat(clamped.getDailyMsgLimit()).isZero();
    }

    @Test
    void clampNeverThrowsOnAnyInput() {
        assertThatCode(() -> policy.clampEnvDefaults(new Quota())).doesNotThrowAnyException();
    }
}
