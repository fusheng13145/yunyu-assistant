package com.leyon.backend.service;

import com.leyon.backend.mapper.InviteCodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 邀请码并发回归（v2.37）
 * <p>
 * 与 {@code QuotaDailyUsageConcurrencyTest} 同一口径：这里的 fake 只<b>建模</b> InnoDB 的
 * {@code UPDATE ... WHERE used_by IS NULL} 行锁语义（首胜者写入、后来者影响 0 行），
 * 不构成对该语义的证明——真实库并发复跑另见手册 7.4 v2.37 台账。
 * 附一条确定性反例：若把领取写成"先查未使用再更新"，同一个码会被多人共用；
 * 反例若没能复现超发，说明 fake 建模有误，前面的断言也就全假。
 *
 * @author leyon
 */
class InviteCodeConcurrencyTest {

    private static final String CODE = "SHARE123456";
    private static final int THREADS = 20;

    /** 模拟 invite_code 表：code -> used_by（空串 = 未领取） */
    private final Map<String, String> table = new ConcurrentHashMap<>();

    private InviteCodeService service;

    @BeforeEach
    void setUp() {
        table.put(CODE, "");
        InviteCodeMapper mapper = mock(InviteCodeMapper.class);
        // 原子领取：检查与写入在同一条语句内（fake 用 put 的返回值表达同一不可分条件）
        when(mapper.claimByCode(anyString(), anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            String userId = inv.getArgument(1);
            if (!CODE.equals(code)) {
                return 0;
            }
            String previous = table.put(code, userId);
            return "".equals(previous) ? 1 : 0;
        });
        service = new InviteCodeService(mapper, "invite");
    }

    /**
     * @param winners      领取成功数
     * @param windowOpened 反例栅栏被"全体到齐"触发的次数，是竞态窗口确实开放过的直接证据
     */
    private record Outcome(int winners, int windowOpened) {
    }

    private Outcome claimAll(boolean atomic) throws Exception {
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger windowOpened = new AtomicInteger();
        // 栅栏卡在"查到未使用之后、写入领取之前"：全部线程基于同一快照判断，超发数因此是确定值
        // 而不是调度巧合（栅栏只算工作线程，测试线程不参与，否则 parties 少 1 会全体阻塞）
        CyclicBarrier afterCheck = new CyclicBarrier(THREADS, windowOpened::incrementAndGet);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < THREADS; i++) {
            final String userId = "u-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(10, TimeUnit.SECONDS);
                    if (atomic) {
                        if (service.claim(CODE, userId)) {
                            winners.incrementAndGet();
                        }
                    } else {
                        // 反例：旧写法——先查"未使用"再更新，两步之间没有任何锁定
                        boolean unused = "".equals(table.get(CODE));
                        afterCheck.await(10, TimeUnit.SECONDS);
                        if (unused) {
                            table.put(CODE, userId);
                            winners.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (BrokenBarrierException | TimeoutException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return new Outcome(winners.get(), windowOpened.get());
    }

    @Test
    void concurrentClaims_admitExactlyOneUser() throws Exception {
        assertThat(claimAll(true).winners()).isEqualTo(1);
        assertThat(table.get(CODE)).isNotBlank();
    }

    @Test
    void checkThenActWouldShareOneCodeWithEveryone() throws Exception {
        // 这条测试断言的是 fake 的建模能力：证明"先查后改"确实会全部放行
        Outcome outcome = claimAll(false);

        assertThat(outcome.windowOpened()).as("栅栏应在超时前被全部线程触发（竞态窗口开放的确证）")
                .isEqualTo(1);
        assertThat(outcome.winners()).as("先查后领应让 %d 个请求共用同一个码", THREADS)
                .isEqualTo(THREADS);
    }
}
