package com.rrmadon.flashsale.chaos;

import com.rrmadon.flashsale.reservation.ReservationStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Card 25: Card 7's fail-closed test (and Palang's own fail-open test) use
 * a simulated failing store -- a fake that throws on command. That proves
 * the code path exists; it doesn't prove the behaviour survives contact
 * with an actual dying Redis under actual load.
 *
 * This stops the real flash-sale-queue-redis container mid-test and
 * confirms claim() fails closed against it -- an exception, not a silent
 * "sure, here's a unit." Opt-in via -Dchaos.tests=true: it manipulates
 * real infrastructure this machine happens to have running, which no
 * other test in this suite should depend on, and which a CI environment
 * or another developer's machine may not have shaped the same way.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "chaos.tests", matches = "true")
class RedisChaosTest {

    private static final String CONTAINER = "flash-sale-queue-redis";

    @Autowired
    private ReservationStore store;

    @BeforeAll
    static void ensureContainerRunningBeforeWeStartMessingWithIt() throws Exception {
        run("docker", "start", CONTAINER);
        waitUntilReachable();
    }

    @AfterAll
    static void restoreContainerRegardlessOfTestOutcome() throws Exception {
        run("docker", "start", CONTAINER);
        waitUntilReachable();
    }

    @Test
    void claimFailsClosedWhenRedisIsActuallyStopped() throws Exception {
        String sku = "chaos-" + UUID.randomUUID();
        // Sanity: works normally before we break anything.
        assertThat(store.claim(sku, Duration.ofMinutes(5)).allowed()).isFalse(); // unseeded, expected

        run("docker", "stop", CONTAINER);
        try {
            assertThatThrownBy(() -> store.claim("chaos-live-" + UUID.randomUUID(), Duration.ofMinutes(5)))
                    .as("a claim against a genuinely unreachable Redis must fail loudly, never silently allow")
                    .isInstanceOf(Exception.class);
        } finally {
            run("docker", "start", CONTAINER);
            waitUntilReachable();
        }

        // Recovery: the same store, same app, works again once Redis is back --
        // no restart of the application itself required. But "the container
        // answers PING again" and "the app's own connection is fully usable
        // again" are not the same instant: a container restart wipes Redis's
        // server-side Lua script cache, and Lettuce's pooled connection can
        // take a beat to notice the reconnect and re-EVAL (vs EVALSHA) the
        // script. A single immediate call genuinely failed here the first
        // time this test was run -- a real finding a mocked failure could
        // never have produced, and exactly why this test manipulates real
        // infrastructure instead of a fake. Retry with backoff rather than
        // assert instantly, since the same caveat applies to any real
        // Sentinel failover, not just this container restart.
        assertRecoversWithinRetries(() ->
                store.claim("chaos-recovered-" + UUID.randomUUID(), Duration.ofMinutes(5)));
    }

    private static void assertRecoversWithinRetries(Runnable attempt) throws InterruptedException {
        Exception last = null;
        for (int i = 0; i < 10; i++) {
            try {
                attempt.run();
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(300);
            }
        }
        throw new AssertionError("store did not recover within retries after Redis restart", last);
    }

    private static void run(String... cmd) throws Exception {
        new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    private static void waitUntilReachable() throws Exception {
        for (int i = 0; i < 20; i++) {
            Process p = new ProcessBuilder("docker", "exec", CONTAINER, "redis-cli", "PING")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            if (out.contains("PONG")) return;
            Thread.sleep(300);
        }
        throw new IllegalStateException("Redis did not become reachable again in time");
    }
}
