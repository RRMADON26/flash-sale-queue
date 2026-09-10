package com.rrmadon.flashsale.reservation;

import com.rrmadon.flashsale.util.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {com.rrmadon.flashsale.FlashSaleApplication.class, ReservationCleanupServiceTest.TestClockConfig.class})
class ReservationCleanupServiceTest {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-10T12:00:00Z"));
        }
        @Bean
        @Primary
        Clock testClock(MutableClock mutableClock) {
            return mutableClock;
        }
    }

    @Autowired
    private ReservationStore store;
    @Autowired
    private ReservationCleanupService cleanup;
    @Autowired
    private MutableClock clock;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void abandonedReservationRefundsStockAfterItsDeadline() {
        String sku = "cleanup-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, "1");

        ClaimResult claimed = store.claim(sku, Duration.ofMinutes(5));
        assertThat(claimed.allowed()).isTrue();
        assertThat(redis.opsForValue().get("stock:" + sku)).isEqualTo("0");

        // Before the deadline: nothing to refund yet.
        int refundedEarly = cleanup.sweep(clock.instant());
        assertThat(refundedEarly).isZero();
        assertThat(redis.opsForValue().get("stock:" + sku)).isEqualTo("0");

        // Move past the 5-minute deadline -- no sleeping, just move the clock.
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        int refunded = cleanup.sweep(clock.instant());
        assertThat(refunded).isEqualTo(1);
        assertThat(redis.opsForValue().get("stock:" + sku)).isEqualTo("1");
    }

    @Test
    void completedReservationIsNotDoubleRefunded() {
        String sku = "cleanup-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, "1");

        ClaimResult claimed = store.claim(sku, Duration.ofMinutes(5));
        store.release(claimed.sku(), claimed.reservationToken()); // simulates a completed checkout

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        int refunded = cleanup.sweep(clock.instant());
        assertThat(refunded)
                .as("a reservation already released by checkout must not also be refunded by the sweep")
                .isZero();
        assertThat(redis.opsForValue().get("stock:" + sku))
                .as("stock stays at 0 -- release() means the sale went through, not that it's available again")
                .isEqualTo("0");
    }
}
