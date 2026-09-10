package com.rrmadon.flashsale.release;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReleaseControllerTest {

    @Autowired
    private com.rrmadon.flashsale.queue.QueueStore queue;
    @Autowired
    private ReleaseController release;
    @Autowired
    private CheckoutMetrics metrics;

    // --- Card 12: releases admit in FIFO order at the configured batch size ---

    @Test
    void releaseNextAdmitsEarliestArrivalsFirst() {
        String sku = "rel-" + UUID.randomUUID();
        String t1 = queue.join(sku);
        String t2 = queue.join(sku);
        queue.join(sku); // t3, stays queued

        Set<String> admitted = release.releaseNext(sku); // base batch size, no errors yet -> full batch
        // Only assert the two earliest are in whatever got admitted (batch
        // size is config-driven, not hardcoded here).
        assertThat(admitted).contains(t1, t2);
        assertThat(release.isAdmitted(sku, t1)).isTrue();
        assertThat(release.isAdmitted(sku, t2)).isTrue();
    }

    // --- Card 13: the calibration policy itself, as a pure function ---

    @Test
    void batchSizeStaysAtBaseWhenNoErrors() {
        assertThat(ReleaseController.nextBatchSize(50, 0.0)).isEqualTo(50);
    }

    @Test
    void batchSizeHalvesAboveTenPercentErrorRate() {
        assertThat(ReleaseController.nextBatchSize(50, 0.15)).isEqualTo(25);
    }

    @Test
    void batchSizeDropsSharplyAboveFiftyPercentErrorRate() {
        assertThat(ReleaseController.nextBatchSize(50, 0.6)).isEqualTo(5);
    }

    @Test
    void injectedFailureRateVisiblyReducesReleaseBatchSize_andRecoversAfter() {
        String sku = "rel-calib-" + UUID.randomUUID();
        for (int i = 0; i < 20; i++) queue.join(sku);

        metrics.reset();
        // Inject a high failure rate -- simulates checkout genuinely erroring.
        for (int i = 0; i < 8; i++) metrics.recordError();
        for (int i = 0; i < 2; i++) metrics.recordSuccess();
        assertThat(metrics.errorRate()).isGreaterThan(0.5);

        Set<String> admittedDuringFailure = release.releaseNext(sku);
        assertThat(admittedDuringFailure.size())
                .as("a high error rate must visibly shrink the admitted batch")
                .isLessThan(20);

        // Recovery: failures stop, successes dominate.
        metrics.reset();
        for (int i = 0; i < 20; i++) metrics.recordSuccess();
        assertThat(metrics.errorRate()).isZero();

        Set<String> admittedAfterRecovery = release.releaseNext(sku);
        assertThat(admittedAfterRecovery.size())
                .as("batch size must recover once the error rate drops")
                .isGreaterThan(admittedDuringFailure.size());
    }
}
