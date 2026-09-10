package com.rrmadon.flashsale.reservation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisReservationStore implements ReservationStore {

    private static final RedisScript<List> CLAIM_SCRIPT = loadClaimScript();

    private final StringRedisTemplate redis;

    public RedisReservationStore(StringRedisTemplate redis) {
        this.redis = redis;
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
        List<?> reply = redis.execute(
                CLAIM_SCRIPT,
                List.of("stock:" + sku),
                String.valueOf(ttl.toSeconds()),
                token
        );

        long allowed = ((Number) reply.get(0)).longValue();
        if (allowed == 0) {
            return ClaimResult.rejected();
        }
        long remaining = ((Number) reply.get(1)).longValue();
        return new ClaimResult(true, remaining, token);
    }

    @Override
    public void release(String reservationToken) {
        redis.delete("reservation:" + reservationToken);
    }
}
