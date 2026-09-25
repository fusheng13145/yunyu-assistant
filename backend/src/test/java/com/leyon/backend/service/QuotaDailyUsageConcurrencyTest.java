package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.leyon.backend.entity.Quota;
import com.leyon.backend.entity.QuotaDailyUsage;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单日配额原子扣减的并发回归（v2.35 资金防线）
 * <p>
 * 被测的是 {@link QuotaService#consumeDaily} 与 mapper 之间的<b>时序契约</b>：
 * "查余额"与"扣减"必须收在同一条带 {@code used < limit} 条件的 UPDATE 里，
 * 建行撞唯一键后不得盲目再扣。FakeUsageMapper 用 CAS 把"带余额条件的 UPDATE"建模为
 * 不可分割的读-比较-写（对应 InnoDB 的 UPDATE 行锁），从而在纯 JVM 内复现竞态窗口。
 * <p>
 * 边界如实声明：真实 MySQL 行锁与唯一索引语义不在 JVM 单测覆盖范围内，须按手册 6.4
 * 的迁移路径对真实库跑一次并发验证（本批未执行，见 7.4 C-7x 登记）。
 *
 * @author leyon
 */
class QuotaDailyUsageConcurrencyTest {

    /** 当日用量行的内存建模：used 只经 CAS 推进，对应 InnoDB 的条件 UPDATE 行锁 */
    private static final class UsageRow {
        final AtomicInteger used = new AtomicInteger();

        /**
         * 等价于 {@code UPDATE ... SET used=used+1 WHERE used < limit}：
         * 只有 CAS 成功才推进 used——条件不满足时真实 SQL 不改写任何行，
         * 若这里无条件 increment 再比较，fake 自己就超发了，被测代码无从验证。
         */
        boolean conditionalConsume(int limit) {
            while (true) {
                int current = used.get();
                if (current >= limit) {
                    return false;
                }
                if (used.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }
    }

    /**
     * 条件扣减桩：{@link UsageRow#conditionalConsume} 建模带余额条件的原子 UPDATE。
     * slowInsertWindow=true 时，insert 前让出时间片，把"selectCount 判无行 → 多人同时 insert"
     * 的窗口撑开，强制走 DuplicateKeyException 分支。
     */
    private static final class FakeUsageMapper extends QuotaDailyUsageMapperStub {
        final Map<String, UsageRow> rows = new ConcurrentHashMap<>();
        final AtomicInteger inserts = new AtomicInteger();
        volatile boolean slowInsertWindow = false;

        private String key(String scopeType, String scopeId, LocalDate date, String metric) {
            return scopeType + "|" + scopeId + "|" + date + "|" + metric;
        }

        @Override
        public int updateDailyUsage(String scopeType, String scopeId, String metric,
                                    LocalDate usageDate, int limit) {
            UsageRow row = rows.get(key(scopeType, scopeId, usageDate, metric));
            if (row == null) {
                return 0; // 与真实 SQL 一致：无当日行时影响 0 行
            }
            return row.conditionalConsume(limit) ? 1 : 0;
        }

        @Override
        public Long selectCount(Wrapper<QuotaDailyUsage> queryWrapper) {
            // 本测试只在单作用域/单指标下跑，故按"表内行数"判断即可
            return (long) rows.size();
        }

        @Override
        public int insert(QuotaDailyUsage entity) {
            if (slowInsertWindow) {
                Thread.yield();
            }
            String key = key(entity.getScopeType(), entity.getScopeId(),
                    entity.getUsageDate(), entity.getMetric());
            // 等价于 UNIQUE(scope_type, scope_id, usage_date, metric)
            if (rows.putIfAbsent(key, new UsageRow()) != null) {
                throw new DuplicateKeyException("Duplicate entry for key 'uk_usage_scope'");
            }
            inserts.incrementAndGet();
            return 1;
        }
    }

    private List<Boolean> hammer(FakeUsageMapper mapper, int threads, int limit) throws Exception {
        QuotaService service = new QuotaService(null, mapper, null, null, null, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        List<Boolean> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(service.consumeDaily(Quota.SCOPE_USER, "u1",
                            QuotaDailyUsage.METRIC_DAILY_MSG, limit));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).as("全部线程应在时限内结束").isTrue();
        pool.shutdownNow();
        return results;
    }

    private long passed(List<Boolean> results) {
        return results.stream().filter(Boolean::booleanValue).count();
    }

    private UsageRow todayRow(FakeUsageMapper mapper) {
        return mapper.rows.get(Quota.SCOPE_USER + "|u1|" + LocalDate.now()
                + "|" + QuotaDailyUsage.METRIC_DAILY_MSG);
    }

    private int finalUsed(FakeUsageMapper mapper) {
        UsageRow row = todayRow(mapper);
        return row == null ? 0 : row.used.get();
    }

    @Test
    @DisplayName("20 并发抢 5 个额度：恰好放行 5 次，used 恰为 5，当日行只建一次")
    void concurrentConsumptionNeverExceedsLimit() throws Exception {
        FakeUsageMapper mapper = new FakeUsageMapper();

        List<Boolean> results = hammer(mapper, 20, 5);

        assertThat(passed(results)).isEqualTo(5);
        assertThat(finalUsed(mapper)).isEqualTo(5);
        assertThat(mapper.inserts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("建行窗口被撑开（多线程同时判无行、同时 insert）：仍不超发、只留一行")
    void insertRaceStillRespectsLimit() throws Exception {
        FakeUsageMapper mapper = new FakeUsageMapper();
        mapper.slowInsertWindow = true;

        List<Boolean> results = hammer(mapper, 20, 5);

        assertThat(passed(results)).isEqualTo(5);
        assertThat(mapper.rows.size()).isEqualTo(1);
        assertThat(finalUsed(mapper)).isEqualTo(5);
    }

    @Test
    @DisplayName("上限 1、并发 8：只放行 1 次（回归 check-then-act 旧写法的超发形状）")
    void singleSlotIsNotOversold() throws Exception {
        FakeUsageMapper mapper = new FakeUsageMapper();

        List<Boolean> results = hammer(mapper, 8, 1);

        assertThat(passed(results)).isEqualTo(1);
        assertThat(finalUsed(mapper)).isEqualTo(1);
    }

    @Test
    @DisplayName("旧的先查后扣写法在本竞态下必然超发（说明本测试真能抓到回归）")
    void naiveCheckThenActWouldOverissue() throws Exception {
        // 反例基线：读余额与扣减之间没有原子性 ⇒ 超发是语义保证而非概率。
        // 用栅栏把"读后、写前"的窗口钉死，2×limit 个线程全部读到同一余额，放行数可精确预期。
        // 若本用例不超发，说明 Fake 的建模有问题，前面几条断言全是假的。
        FakeUsageMapper mapper = new FakeUsageMapper();
        QuotaDailyUsage seed = new QuotaDailyUsage();
        seed.setScopeType(Quota.SCOPE_USER);
        seed.setScopeId("u1");
        seed.setMetric(QuotaDailyUsage.METRIC_DAILY_MSG);
        seed.setUsageDate(LocalDate.now());
        seed.setUsed(0);
        mapper.insert(seed);
        UsageRow row = todayRow(mapper);
        assertThat(row).as("种子行应按被测代码同款 key 建出来").isNotNull();

        int limit = 5;
        int threads = 2 * limit;
        AtomicInteger passedCount = new AtomicInteger();
        // 栅栏卡在"读余额之后、写余额之前"：全部线程基于同一快照判断 ⇒ 超发是确定值，不靠调度运气。
        // 栅栏只算工作线程（测试线程不参与，否则 parties 少 1 会全体 deadlock）；
        // 栅栏动作 trips 仅在 parties 全部到齐后 +1，是"窗口确实开放过"的直接证据。
        CountDownLatch arrived = new CountDownLatch(threads);
        AtomicInteger trips = new AtomicInteger();
        CyclicBarrier afterRead = new CyclicBarrier(threads, trips::incrementAndGet);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    int snapshot = row.used.get();
                    arrived.countDown();
                    afterRead.await();
                    if (snapshot < limit) {
                        row.used.incrementAndGet();
                        passedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (BrokenBarrierException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(arrived.await(10, TimeUnit.SECONDS))
                .as("全部线程应完成读快照；超时说明任务没跑起来，与竞态无关").isTrue();
        assertThat(trips.get()).as("栅栏应在超时前被全部线程触发（竞态窗口开放的确证）").isEqualTo(1);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(passedCount.get()).as("先查后扣应让全部 2N 个线程都通过 N 的限额")
                .isEqualTo(threads);
        assertThat(finalUsed(mapper)).isEqualTo(threads);
    }
}
