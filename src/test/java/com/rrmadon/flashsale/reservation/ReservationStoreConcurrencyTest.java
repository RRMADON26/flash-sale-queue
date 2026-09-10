package com.rrmadon.flashsale.reservation;

import com.rrmadon.palang.testkit.ConcurrentCallers;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The load-bearing test for the whole project. Nothing in milestones 2-4
 * should start before this is green: it's the proof that the atomic claim
 * actually holds under real concurrency, not just in a sequential test that
 * never gives a race the chance to happen.
 */
@SpringBootTest
class ReservationStoreConcurrencyTest {

    private static final int CALLERS = 64;
    private static final int STOCK = 10;

    @Autowired
    private ReservationStore store;

    @Autowired
    private StringRedisTemplate redis;

    @RepeatedTest(3)
    void exactlyStockCountSucceedsUnderConcurrency() throws Exception {
        String sku = "concurrency-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, String.valueOf(STOCK));

        ConcurrentCallers.CallerResults<ClaimResult> results = ConcurrentCallers.of(CALLERS)
                .fire(() -> store.claim(sku, Duration.ofMinutes(5)))
                .assertNoFailures();

        long admitted = results.count(ClaimResult::allowed);
        long rejected = results.count(r -> !r.allowed());

        assertThat(admitted)
                .as("exactly %d of %d concurrent callers must be admitted, never more", STOCK, CALLERS)
                .isEqualTo(STOCK);
        assertThat(rejected).isEqualTo(CALLERS - STOCK);

        long finalStock = Long.parseLong(redis.opsForValue().get("stock:" + sku));
        assertThat(finalStock).isZero();
    }
}
