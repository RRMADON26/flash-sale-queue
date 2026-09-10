package com.rrmadon.flashsale.reservation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

@Component
public class RedisReservationStore implements ReservationStore {

    private static final RedisScript<List> CLAIM_SCRIPT = loadClaimScript();

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisReservationStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    private static RedisScript<List> loadClaimScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/claim.lua"));
        script.setResultType(List.class);
        script.afterPropertiesSet();
        return script;
    }

    @Override
    public ClaimResult claim(String sku, Duration ttl) {
        String token = ReservationStore.newToken();
        Instant now = Instant.now(clock);

        List<?> reply = redis.execute(
                CLAIM_SCRIPT,
                List.of("stock:" + sku, ReservationCleanupService.PENDING_KEY),
                String.valueOf(ttl.toSeconds()),
                token,
                sku,
                String.valueOf(now.getEpochSecond())
        );

        long allowed = ((Number) reply.get(0)).longValue();
        if (allowed == 0) {
            return ClaimResult.rejected();
        }
        long remaining = ((Number) reply.get(1)).longValue();
        return new ClaimResult(true, remaining, token, sku);
    }

    @Override
    public void release(String sku, String reservationToken) {
        redis.opsForZSet().remove(ReservationCleanupService.PENDING_KEY, sku + ":" + reservationToken);
    }
}
