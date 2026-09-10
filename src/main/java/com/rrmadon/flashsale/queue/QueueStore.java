package com.rrmadon.flashsale.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The queue every opening-second request lands in: a Redis sorted set keyed
 * by SKU, scored by an atomically incrementing sequence number. ZRANGE/ZRANK
 * give strict FIFO order and position lookup for free -- this is
 * deliberately the cheapest component in the pipeline, since it's the one
 * every caller touches (architecture doc §03).
 *
 * The score is a Redis-side INCR sequence, not a wall-clock timestamp.
 * Millisecond timestamps were the first version of this and looked correct
 * under manual testing -- they broke under a real burst test, where two
 * joins landing in the same millisecond tie, and Redis's ZADD tie-break for
 * equal scores is lexicographic by member (a random UUID), not insertion
 * order. That silently violates the FIFO guarantee this class exists to
 * provide, exactly under the flash-sale conditions it's meant for. An
 * INCR-based sequence cannot tie.
 */
@Component
public class QueueStore {

    private final StringRedisTemplate redis;

    public QueueStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String sku) {
        return "queue:" + sku;
    }

    private String seqKey(String sku) {
        return "queue:" + sku + ":seq";
    }

    /** Issues a ticket and adds it to {@code sku}'s queue at the next sequence position. */
    public String join(String sku) {
        String ticketId = UUID.randomUUID().toString();
        long seq = redis.opsForValue().increment(seqKey(sku));
        redis.opsForZSet().add(key(sku), ticketId, seq);
        return ticketId;
    }

    /**
     * Current 0-based rank of {@code ticketId} in {@code sku}'s queue.
     *
     * @return empty if the ticket isn't in the queue (never joined, or
     *         already popped by the Release Controller)
     */
    public Optional<Long> positionOf(String sku, String ticketId) {
        return Optional.ofNullable(redis.opsForZSet().rank(key(sku), ticketId));
    }

    /** Removes and returns up to {@code n} tickets with the earliest arrival. */
    public java.util.Set<String> popEarliest(String sku, long n) {
        var popped = redis.opsForZSet().popMin(key(sku), n);
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (popped != null) {
            popped.forEach(t -> { if (t.getValue() != null) ids.add(t.getValue()); });
        }
        return ids;
    }
}
