package com.rrmadon.flashsale.release;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks checkout success/error counts for two audiences at once: Micrometer
 * counters for real observability (Card 23), and plain AtomicLongs the
 * Release Controller reads directly to calibrate admission rate -- simpler
 * and more directly testable than parsing Micrometer's own aggregation for
 * a decision made every few seconds.
 *
 * "Error" means the system failed (an exception, a 5xx) -- not a 404 for an
 * unknown/expired reservation, which is an expected, benign outcome and
 * says nothing about checkout's health. Counting it as an error would make
 * the Release Controller back off in response to normal client behaviour.
 */
@Component
public class CheckoutMetrics {

    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Counter successCounter;
    private final Counter errorCounter;

    public CheckoutMetrics(MeterRegistry registry) {
        this.successCounter = Counter.builder("checkout.success").register(registry);
        this.errorCounter = Counter.builder("checkout.error").register(registry);
    }

    public void recordSuccess() {
        successes.incrementAndGet();
        successCounter.increment();
    }

    public void recordError() {
        errors.incrementAndGet();
        errorCounter.increment();
    }

    /** @return error rate in [0,1], or 0 if there's no data yet -- don't back off on silence. */
    public double errorRate() {
        long s = successes.get();
        long e = errors.get();
        long total = s + e;
        return total == 0 ? 0.0 : (double) e / total;
    }

    public void reset() {
        successes.set(0);
        errors.set(0);
    }
}
