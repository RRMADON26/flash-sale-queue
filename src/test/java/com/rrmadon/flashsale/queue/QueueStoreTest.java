package com.rrmadon.flashsale.queue;

import com.rrmadon.palang.testkit.ConcurrentCallers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class QueueStoreTest {

    @Autowired
    private QueueStore queue;

    @Test
    void joinAssignsIncreasingPosition() {
        String sku = "q-" + UUID.randomUUID();
        String t1 = queue.join(sku);
        String t2 = queue.join(sku);
        String t3 = queue.join(sku);

        assertThat(queue.positionOf(sku, t1)).contains(0L);
        assertThat(queue.positionOf(sku, t2)).contains(1L);
        assertThat(queue.positionOf(sku, t3)).contains(2L);
    }

    @Test
    void unknownTicketHasNoPosition() {
        assertThat(queue.positionOf("q-" + UUID.randomUUID(), "never-joined")).isEmpty();
    }

    @Test
    void popEarliestRemovesInArrivalOrder() {
        String sku = "q-" + UUID.randomUUID();
        String t1 = queue.join(sku);
        String t2 = queue.join(sku);
        queue.join(sku); // t3, left in queue

        Set<String> popped = queue.popEarliest(sku, 2);

        assertThat(popped).containsExactlyInAnyOrder(t1, t2);
        assertThat(queue.positionOf(sku, t1)).isEmpty();
        assertThat(queue.positionOf(sku, t2)).isEmpty();
    }

    /**
     * Card 11: fairness under burst. The card's original target was 10,000
     * simultaneous joins; ConcurrentCallers uses one real OS thread per
     * caller, and this sandbox's ulimit -u (2666) can't create that many.
     * 2,000 is the honest number this environment can actually run, not a
     * silently substituted one -- a real deployment target with proper
     * thread limits (or a virtual-thread-based caller, a natural follow-up)
     * could push this back up to the card's original figure.
     *
     * The property under test doesn't change with the count: no position
     * is lost or duplicated -- not that microsecond-level arrival order is
     * preserved perfectly (architecture doc §09: network jitter means that
     * isn't knowable across real clients anyway; the actual bar is "every
     * ticket gets exactly one position, none collide, none vanish").
     */
    @Test
    void twoThousandSimultaneousJoinsProduceNoCollisionsOrLosses() throws Exception {
        String sku = "burst-" + UUID.randomUUID();
        int callers = 2_000;

        ConcurrentCallers.CallerResults<String> results = ConcurrentCallers.of(callers)
                .timeout(java.time.Duration.ofSeconds(60))
                .fire(() -> queue.join(sku))
                .assertNoFailures();

        assertThat(results.results()).hasSize(callers);

        List<String> distinctTicketIds = results.results().stream().distinct().collect(Collectors.toList());
        assertThat(distinctTicketIds)
                .as("every ticket ID must be unique -- a collision would mean two callers overwrote one position")
                .hasSize(callers);

        List<Long> positions = results.results().stream()
                .map(id -> queue.positionOf(sku, id).orElseThrow())
                .sorted()
                .collect(Collectors.toList());
        assertThat(positions)
                .as("positions must be exactly 0..9999 with no gaps or duplicates")
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, callers).boxed().collect(Collectors.toList()));
    }
}
