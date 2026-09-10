package com.rrmadon.flashsale.reservation;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * Returns stock held by reservations that were never completed.
 *
 * Deliberately does not rely on Redis's own key-TTL expiry: that timer runs
 * on Redis's internal clock, which a Java test has no way to fake or
 * advance, so a correctness test for "does an abandoned reservation return
 * stock" could only ever be a real-time sleep. Instead, each claim records
 * its own logical expiresAt (epoch seconds) in a sorted set, and this
 * service compares that against an injected {@link Clock} -- exactly the
 * same testability pattern Palang itself uses throughout.
 */
@Service
public class ReservationCleanupService {

    static final String PENDING_KEY = "reservations:pending";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public ReservationCleanupService(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Real entry point: runs on the system clock, every 5 seconds. */
    @Scheduled(fixedDelay = 5000)
    public void sweep() {
        sweep(Instant.now(clock));
    }

    /**
     * Refunds stock for every reservation whose logical deadline is at or
     * before {@code now}. Exposed with an explicit instant so tests can
     * drive it precisely without waiting on real time.
     *
     * @return how many reservations were refunded
     */
    public int sweep(Instant now) {
        Set<ZSetOperations.TypedTuple<String>> expired = redis.opsForZSet()
                .rangeByScoreWithScores(PENDING_KEY, 0, now.getEpochSecond());

        if (expired == null || expired.isEmpty()) {
            return 0;
        }

        int refunded = 0;
        for (ZSetOperations.TypedTuple<String> entry : expired) {
            String member = entry.getValue();
            if (member == null) continue;
            int sep = member.indexOf(':');
            if (sep < 0) continue;
            String sku = member.substring(0, sep);

            // Remove first, then refund: if this process dies between the
            // two, the worst case is a reservation that's silently dropped
            // without a refund (undercounts availability) rather than a
            // refund applied twice (oversells) -- the same asymmetric
            // preference for "fail toward less stock, never more" as the
            // claim script itself.
            Long removed = redis.opsForZSet().remove(PENDING_KEY, member);
            if (removed != null && removed > 0) {
                redis.opsForValue().increment("stock:" + sku);
                refunded++;
            }
        }
        return refunded;
    }
}
