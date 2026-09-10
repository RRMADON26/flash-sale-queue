package com.rrmadon.flashsale.release;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Halts admission without a deploy. A flash sale's live window is minutes,
 * not hours -- there's no time to roll back and redeploy if something's
 * visibly wrong mid-sale (Card 22). Backed by Redis, not an in-process
 * flag, so it takes effect on every app instance immediately, not just the
 * one an admin happens to hit.
 */
@Component
public class KillSwitch {

    private static final String KEY = "admission:halted";

    private final StringRedisTemplate redis;

    public KillSwitch(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void halt() {
        redis.opsForValue().set(KEY, "true");
    }

    public void resume() {
        redis.delete(KEY);
    }

    public boolean isHalted() {
        return Boolean.TRUE.equals(redis.hasKey(KEY));
    }
}
