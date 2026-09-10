package com.rrmadon.flashsale.release;

import com.rrmadon.flashsale.queue.QueueStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Calibrates how many tickets move from "queued" to "admitted" per tick,
 * against checkout's real error rate -- architecture doc §04: back off when
 * checkout starts erroring, not just when it's slow. The one component in
 * this project whose correctness is a tuning problem, not a binary proof
 * (architecture doc §10): {@link #nextBatchSize} is deliberately simple
 * and directly testable rather than a black box, so the calibration policy
 * itself can be inspected and argued with, not just trusted.
 */
@Service
public class ReleaseController {

    private final QueueStore queue;
    private final StringRedisTemplate redis;
    private final CheckoutMetrics metrics;
    private final int baseBatchSize;
    private final Duration admittedTtl;

    public ReleaseController(QueueStore queue, StringRedisTemplate redis, CheckoutMetrics metrics,
                              @Value("${flashsale.release.base-batch-size:50}") int baseBatchSize,
                              @Value("${flashsale.release.admitted-ttl:5m}") Duration admittedTtl) {
        this.queue = queue;
        this.redis = redis;
        this.metrics = metrics;
        this.baseBatchSize = baseBatchSize;
        this.admittedTtl = admittedTtl;
    }

    /**
     * The calibration policy itself, isolated from Redis/scheduling so it's
     * testable as a pure function: given the current error rate, how many
     * tickets should this tick admit?
     *
     * Thresholds are intentionally coarse steps, not a continuous curve --
     * a flash sale's operator needs to be able to say "at what error rate
     * does this back off" in one sentence, not read a formula.
     */
    static int nextBatchSize(int base, double errorRate) {
        if (errorRate > 0.5) return Math.max(1, base / 10);
        if (errorRate > 0.1) return Math.max(1, base / 2);
        return base;
    }

    /** Real entry point: runs on a fixed interval against the configured SKU. */
    @Scheduled(fixedDelayString = "${flashsale.release.interval:2000}")
    public void tick() {
        String sku = System.getProperty("flashsale.release.sku", "default-sku");
        releaseNext(sku);
    }

    /**
     * Admits up to the calibrated batch size of tickets for {@code sku}.
     *
     * @return the tickets actually admitted this call
     */
    public Set<String> releaseNext(String sku) {
        int batchSize = nextBatchSize(baseBatchSize, metrics.errorRate());
        Set<String> admitted = queue.popEarliest(sku, batchSize);
        for (String ticketId : admitted) {
            redis.opsForValue().set("admitted:" + sku + ":" + ticketId, "true", admittedTtl);
        }
        return admitted;
    }

    public boolean isAdmitted(String sku, String ticketId) {
        return Boolean.TRUE.equals(redis.hasKey("admitted:" + sku + ":" + ticketId));
    }
}
