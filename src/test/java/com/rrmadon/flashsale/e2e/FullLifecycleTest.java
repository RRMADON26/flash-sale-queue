package com.rrmadon.flashsale.e2e;

import com.rrmadon.flashsale.queue.QueueStore;
import com.rrmadon.flashsale.reservation.ClaimResult;
import com.rrmadon.flashsale.reservation.ReservationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card 14: the loop architecture doc §06 opened -- one ticket's full life,
 * closed end to end against a real running server. Join -> admitted ->
 * reserved -> checkout -> Purchased, stock decremented by exactly 1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullLifecycleTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private QueueStore queue;
    @Autowired private com.rrmadon.flashsale.release.ReleaseController release;
    @Autowired private ReservationStore reservations;
    @Autowired private StringRedisTemplate redis;

    @Test
    void joinAdmitReserveCheckout_stockDecrementsByExactlyOne() {
        String sku = "e2e-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, "3");

        // Queued.
        String ticketId = queue.join(sku);
        assertThat(queue.positionOf(sku, ticketId)).contains(0L);

        // Admitted.
        release.releaseNext(sku);
        assertThat(release.isAdmitted(sku, ticketId)).isTrue();
        assertThat(queue.positionOf(sku, ticketId))
                .as("an admitted ticket is popped off the queue")
                .isEmpty();

        // Reserved.
        ClaimResult claim = reservations.claim(sku, Duration.ofMinutes(5));
        assertThat(claim.allowed()).isTrue();
        assertThat(redis.opsForValue().get("stock:" + sku)).isEqualTo("2");

        // Purchased.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "e2e-" + ticketId);
        String body = String.format("{\"sku\":\"%s\",\"reservationToken\":\"%s\"}", sku, claim.reservationToken());
        ResponseEntity<String> checkoutResponse = rest.exchange(
                "/checkout", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(checkoutResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(checkoutResponse.getBody()).contains("\"status\":\"captured\"");

        // Exactly one unit gone -- not zero (lost), not two (oversold).
        assertThat(redis.opsForValue().get("stock:" + sku))
                .as("stock decremented by exactly 1 across the whole journey")
                .isEqualTo("2");
    }
}
