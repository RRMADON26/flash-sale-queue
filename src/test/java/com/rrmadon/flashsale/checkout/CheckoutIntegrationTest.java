package com.rrmadon.flashsale.checkout;

import com.rrmadon.flashsale.reservation.ReservationCleanupService;
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
 * Card 8: real HTTP round trips (not mocked) for the scenarios this whole
 * project exists to prevent -- proving Palang's guarantees hold at the one
 * endpoint that talks to money, not just trusting Palang's own test suite
 * from a different repository.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redis;

    private String seedReservation(String sku) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set("stock:" + sku, "5");
        long expiresAt = System.currentTimeMillis() / 1000 + 300;
        redis.opsForZSet().add(ReservationCleanupService.PENDING_KEY, sku + ":" + token, expiresAt);
        return token;
    }

    private ResponseEntity<String> checkout(String idempotencyKey, String sku, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        String body = String.format("{\"sku\":\"%s\",\"reservationToken\":\"%s\"}", sku, token);
        return rest.exchange("/checkout", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void duplicateSubmitReplaysByteIdentical() {
        String sku = "it-" + UUID.randomUUID();
        String token = seedReservation(sku);
        String key = "dup-" + UUID.randomUUID();

        ResponseEntity<String> first = checkout(key, sku, token);
        ResponseEntity<String> second = checkout(key, sku, token);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test
    void reusedKeyWithDifferentReservationConflicts() {
        String sku = "it-" + UUID.randomUUID();
        String tokenA = seedReservation(sku);
        String tokenB = seedReservation(sku);
        String key = "conflict-" + UUID.randomUUID();

        checkout(key, sku, tokenA);
        ResponseEntity<String> conflict = checkout(key, sku, tokenB);

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        String sku = "it-" + UUID.randomUUID();
        String token = seedReservation(sku);

        ResponseEntity<String> response = checkout(null, sku, token);

        assertThat(response.getStatusCode().value())
                .as("require-key: true -- checkout must never run unguarded")
                .isEqualTo(400);
    }

    @Test
    void unknownReservationIsRejectedNotSilentlyAccepted() {
        ResponseEntity<String> response = checkout(
                "unknown-" + UUID.randomUUID(), "it-" + UUID.randomUUID(), "does-not-exist");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
