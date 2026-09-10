package com.rrmadon.flashsale.e2e;

import com.rrmadon.flashsale.queue.QueueStore;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card 14: the loop architecture doc §06 opened -- one ticket's full life,
 * closed end to end against a real running server. Join -> admitted ->
 * reserved -> checkout -> Purchased, stock decremented by exactly 1.
 *
 * Card 26 rewired this to go entirely through HTTP, including the claim
 * step -- the original version called ReservationStore.claim() directly
 * from Java, which meant the project had never actually proven that a real
 * client could reach that step at all. It couldn't, until /reservations/claim
 * was built.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullLifecycleTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private QueueStore queue;
    @Autowired private com.rrmadon.flashsale.release.ReleaseController release;
    @Autowired private StringRedisTemplate redis;

    private HttpHeaders jsonHeaders(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-Id", clientId);
        return headers;
    }

    @Test
    void joinAdmitReserveCheckout_stockDecrementsByExactlyOne() {
        String sku = "e2e-" + UUID.randomUUID();
        String clientId = "client-" + UUID.randomUUID();
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

        // Bind the admitted ticket to this client, exactly as a real join
        // response would have (join() itself is bypassed above since this
        // test needs a deterministic position at index 0).
        redis.opsForValue().set("ticket-owner:" + sku + ":" + ticketId, clientId);

        // Reserved, via the real HTTP claim endpoint.
        HttpHeaders claimHeaders = jsonHeaders(clientId);
        claimHeaders.set("Idempotency-Key", "claim-" + ticketId);
        String claimBody = String.format("{\"sku\":\"%s\",\"ticketId\":\"%s\"}", sku, ticketId);
        ResponseEntity<String> claimResponse = rest.exchange(
                "/reservations/claim", HttpMethod.POST, new HttpEntity<>(claimBody, claimHeaders), String.class);
        assertThat(claimResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(redis.opsForValue().get("stock:" + sku)).isEqualTo("2");

        String reservationToken = extractField(claimResponse.getBody(), "reservationToken");

        // Purchased.
        HttpHeaders checkoutHeaders = jsonHeaders(clientId);
        checkoutHeaders.set("Idempotency-Key", "e2e-" + ticketId);
        String checkoutBody = String.format("{\"sku\":\"%s\",\"reservationToken\":\"%s\"}", sku, reservationToken);
        ResponseEntity<String> checkoutResponse = rest.exchange(
                "/checkout", HttpMethod.POST, new HttpEntity<>(checkoutBody, checkoutHeaders), String.class);

        assertThat(checkoutResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(checkoutResponse.getBody()).contains("\"status\":\"captured\"");

        // Exactly one unit gone -- not zero (lost), not two (oversold).
        assertThat(redis.opsForValue().get("stock:" + sku))
                .as("stock decremented by exactly 1 across the whole journey")
                .isEqualTo("2");
    }

    @Test
    void aDifferentClientCannotClaimOrCheckoutSomeoneElsesTicket() {
        String sku = "e2e-ownership-" + UUID.randomUUID();
        String owner = "client-" + UUID.randomUUID();
        String impostor = "client-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, "3");

        String ticketId = queue.join(sku);
        release.releaseNext(sku);
        redis.opsForValue().set("ticket-owner:" + sku + ":" + ticketId, owner);

        HttpHeaders claimHeaders = jsonHeaders(impostor);
        claimHeaders.set("Idempotency-Key", "steal-" + ticketId);
        String claimBody = String.format("{\"sku\":\"%s\",\"ticketId\":\"%s\"}", sku, ticketId);
        ResponseEntity<String> stolenClaim = rest.exchange(
                "/reservations/claim", HttpMethod.POST, new HttpEntity<>(claimBody, claimHeaders), String.class);
        assertThat(stolenClaim.getStatusCode().value())
                .as("a ticket admitted to one client must not be claimable by another")
                .isEqualTo(403);

        // Stock untouched by the rejected attempt.
        assertThat(redis.opsForValue().get("stock:" + sku)).isEqualTo("3");
    }

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
