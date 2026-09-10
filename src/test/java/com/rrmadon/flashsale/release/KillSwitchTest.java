package com.rrmadon.flashsale.release;

import com.rrmadon.flashsale.queue.QueueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KillSwitchTest {

    @Autowired private QueueStore queue;
    @Autowired private ReleaseController release;
    @Autowired private KillSwitch killSwitch;

    @AfterEach
    void resetSwitch() {
        killSwitch.resume();
    }

    @Test
    void haltedSwitchStopsAdmissionWithinOneTick() {
        String sku = "kill-" + UUID.randomUUID();
        queue.join(sku);
        queue.join(sku);

        killSwitch.halt();
        assertThat(killSwitch.isHalted()).isTrue();

        var admitted = release.releaseNext(sku);
        assertThat(admitted)
                .as("no tickets should be admitted while halted, even though the queue has entries")
                .isEmpty();
    }

    @Test
    void resumeRestoresAdmission() {
        String sku = "kill-" + UUID.randomUUID();
        String t1 = queue.join(sku);

        killSwitch.halt();
        assertThat(release.releaseNext(sku)).isEmpty();

        killSwitch.resume();
        assertThat(killSwitch.isHalted()).isFalse();

        var admitted = release.releaseNext(sku);
        assertThat(admitted).contains(t1);
    }
}
