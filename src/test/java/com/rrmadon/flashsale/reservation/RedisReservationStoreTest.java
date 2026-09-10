package com.rrmadon.flashsale.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisReservationStoreTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Autowired
    private ReservationStore store;

    @Autowired
    private StringRedisTemplate redis;

    private String sku;

    @BeforeEach
    void seedStock() {
        sku = "test-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, "3");
    }

    @Test
    void claimSucceedsWhileStockRemains() {
        ClaimResult r1 = store.claim(sku, Duration.ofMinutes(5));
        assertThat(r1.allowed()).isTrue();
        assertThat(r1.remaining()).isEqualTo(2);

        ClaimResult r2 = store.claim(sku, Duration.ofMinutes(5));
        assertThat(r2.remaining()).isEqualTo(1);
    }

    @Test
    void claimRejectsOnceStockIsGone() {
        for (int i = 0; i < 3; i++) {
            assertThat(store.claim(sku, Duration.ofMinutes(5)).allowed()).isTrue();
        }
        ClaimResult rejected = store.claim(sku, Duration.ofMinutes(5));
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
    }

    @Test
    void unseededSkuIsTreatedAsSoldOut() {
        ClaimResult r = store.claim("never-seeded-" + UUID.randomUUID(), Duration.ofMinutes(5));
        assertThat(r.allowed())
                .as("an unknown SKU must never be treated as unlimited stock")
                .isFalse();
    }

    @Test
    void reservationTokenIsUnguessable_notSequential() {
        ClaimResult r1 = store.claim(sku, Duration.ofMinutes(5));
        ClaimResult r2 = store.claim(sku, Duration.ofMinutes(5));

        assertThat(r1.reservationToken()).matches(UUID_PATTERN);
        assertThat(r2.reservationToken()).matches(UUID_PATTERN);
        assertThat(r1.reservationToken()).isNotEqualTo(r2.reservationToken());
    }

    @Test
    void releaseFreesTheReservationKeyButNotStock() {
        ClaimResult r = store.claim(sku, Duration.ofMinutes(5));
        assertThat(redis.hasKey("reservation:" + r.reservationToken())).isTrue();

        store.release(r.reservationToken());

        assertThat(redis.hasKey("reservation:" + r.reservationToken())).isFalse();
        // Releasing does not refund stock -- that's the TTL-expiry cleanup job's
        // job (Card 4), a deliberate separation of concerns.
        assertThat(redis.opsForValue().get("stock:" + sku)).isEqualTo("2");
    }
}
