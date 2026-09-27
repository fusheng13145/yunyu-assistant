package com.leyon.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开放平台拒绝台账单测（v2.50 · 候选 ㉜）
 * <p>
 * 这个类的全部价值在于"基数有界 + 只留近期"，而这两件事都不会在拦截器测试里暴露
 * （那边只有一两个应用、一小时窗口）。所以这里用固定时钟把小时推进变成算术：
 * ①跨小时的同一格在读数里求和成一行（否则一次持续爆破会读出 24 行重复内容）；
 * ②超出保留期的格子真的被删（只看 {@code recent} 是证明不了的，读数窗口本身就会藏掉它）；
 * ③认不出归属的凭据一律折进 unknown 一格——这一条是防"台账自己变成 DoS 面"。
 *
 * @author leyon
 */
class OpenApiDenialMeterTest {

    /** 整点起点：小时桶是毫秒数除以 3600000，取整点可让断言里的桶序号可心算 */
    private static final long HOUR_1000 = TimeUnit.HOURS.toMillis(1000);
    private static final long ONE_HOUR = TimeUnit.HOURS.toMillis(1);

    @Test
    @DisplayName("同一格跨小时：读数按窗口求和成一行，小时桶只是裁剪粒度")
    void hourBucketsAreSummedIntoOneRow() {
        SteadyClock clock = new SteadyClock(HOUR_1000);
        OpenApiDenialMeter meter = new OpenApiDenialMeter(clock);
        for (int i = 0; i < 3; i++) {
            meter.record(OpenApiDenialMeter.Kind.KEY_INVALID, "app-1");
        }
        clock.advanceMillis(ONE_HOUR);
        for (int i = 0; i < 2; i++) {
            meter.record(OpenApiDenialMeter.Kind.KEY_INVALID, "app-1");
        }

        List<OpenApiDenialMeter.Entry> window24 = meter.recent(24);
        assertThat(window24).hasSize(1);
        assertThat(window24.get(0).kind()).isEqualTo(OpenApiDenialMeter.Kind.KEY_INVALID);
        assertThat(window24.get(0).app()).isEqualTo("app-1");
        assertThat(window24.get(0).count()).isEqualTo(5L);
        // 窗口收窄到 1 小时时，只剩当前桶的那部分——求和不能把窗口外的量也算进来
        assertThat(meter.recent(1)).singleElement()
                .extracting(OpenApiDenialMeter.Entry::count).isEqualTo(2L);
    }

    @Test
    @DisplayName("超保留期的格子被删：cellCount 证明是裁剪生效，而不是读数窗口替它遮掩")
    void expiredBucketsArePruned() {
        SteadyClock clock = new SteadyClock(HOUR_1000);
        OpenApiDenialMeter meter = new OpenApiDenialMeter(clock);
        for (int i = 0; i < 10; i++) {
            meter.record(OpenApiDenialMeter.Kind.KEY_INVALID, "app-" + i);
        }
        assertThat(meter.cellCount()).isEqualTo(10);

        // 推进到保留期之外：下一次写入顺带把过期桶删掉
        clock.advanceMillis(ONE_HOUR * (OpenApiDenialMeter.MAX_RETAINED_HOURS + 1));
        meter.record(OpenApiDenialMeter.Kind.KEY_INVALID, "app-now");

        assertThat(meter.cellCount()).isEqualTo(1);
        assertThat(meter.recent(24)).singleElement()
                .extracting(OpenApiDenialMeter.Entry::app).isEqualTo("app-now");
    }

    @Test
    @DisplayName("认不出归属的凭据全部折进 unknown 一格：随机 Key 打不出新格子")
    void unattributableEventsFoldIntoOneCell() {
        OpenApiDenialMeter meter = new OpenApiDenialMeter(new SteadyClock(HOUR_1000));
        for (int i = 0; i < 1000; i++) {
            // 拦截器侧的行为：查不到应用时 appId 传 null，绝不把凭据片段当 id 记进去
            meter.record(OpenApiDenialMeter.Kind.KEY_INVALID, null);
        }
        meter.record(OpenApiDenialMeter.Kind.KEY_MISSING, "   ");

        assertThat(meter.cellCount()).isEqualTo(2);
        assertThat(meter.recent(24))
                .extracting(OpenApiDenialMeter.Entry::count)
                .containsExactlyInAnyOrder(1000L, 1L);
        assertThat(meter.recent(24))
                .extracting(OpenApiDenialMeter.Entry::app)
                .containsOnly(OpenApiDenialMeter.UNKNOWN_APP);
    }

    @Test
    @DisplayName("种类互不合并：同一应用的 403 与 429 是两行，属主要分辨是被人试还是自己刷")
    void kindsDoNotMergeAcrossEventTypes() {
        OpenApiDenialMeter meter = new OpenApiDenialMeter(new SteadyClock(HOUR_1000));
        meter.record(OpenApiDenialMeter.Kind.SCOPE_DENIED, "app-1");
        meter.record(OpenApiDenialMeter.Kind.RATE_LIMITED, "app-1");

        assertThat(meter.recent(24)).hasSize(2)
                .extracting(OpenApiDenialMeter.Entry::kind)
                // 排序按枚举声明序（ordinal），不是名字字母序：RATE_LIMITED 声明在 SCOPE_DENIED 之后
                .containsExactly(OpenApiDenialMeter.Kind.SCOPE_DENIED,
                        OpenApiDenialMeter.Kind.RATE_LIMITED);
    }

    @Test
    @DisplayName("窗口参数越界截断：读数窗口不可能长于台账保留长度，也不允许 0 或负数")
    void clampWindowBoundaries() {
        assertThat(OpenApiDenialMeter.clampWindow(0)).isEqualTo(1);
        assertThat(OpenApiDenialMeter.clampWindow(-5)).isEqualTo(1);
        assertThat(OpenApiDenialMeter.clampWindow(6)).isEqualTo(6);
        assertThat(OpenApiDenialMeter.clampWindow(9999)).isEqualTo(OpenApiDenialMeter.MAX_RETAINED_HOURS);
    }

    @Test
    @DisplayName("每个种类都有唯一中文名：前端直接显示 label()，不能出现空串或撞名")
    void everyKindHasDistinctLabel() {
        Set<String> labels = new HashSet<>();
        List<String> blanks = new ArrayList<>();
        for (OpenApiDenialMeter.Kind kind : OpenApiDenialMeter.Kind.values()) {
            String label = kind.label();
            if (label == null || label.isBlank()) {
                blanks.add(kind.name());
            } else {
                assertThat(labels.add(label)).as("种类 %s 的中文名与已有条目重复", kind).isTrue();
            }
        }
        assertThat(blanks).isEmpty();
    }

    /** 可手动推进的时钟：小时桶是纯算术，测试不必真的等一小时 */
    private static final class SteadyClock extends Clock {
        private long millis;

        SteadyClock(long millis) {
            this.millis = millis;
        }

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
