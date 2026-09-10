package com.rrmadon.flashsale.reservation;

import com.rrmadon.flashsale.release.ReleaseController;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The missing link between "my ticket was admitted" and "I have a
 * reservation to check out with" -- found while implementing Card 26: the
 * project's own end-to-end test had been calling ReservationStore.claim()
 * directly from Java, which meant no real client could actually reach this
 * step over HTTP at all. Requires the ticket to be admitted AND the caller
 * to be who it was admitted to, so a leaked ticket ID alone can't be
 * redeemed by someone else.
 *
 * Like every POST route in this app, Palang's idempotency filter also
 * requires an Idempotency-Key header here (app-global config, see
 * application.yml) -- which is exactly right for this endpoint too: a
 * retried claim (client timeout, mobile network drop) must replay the same
 * reservation, not silently burn a second unit of stock.
 */
@RestController
public class ReservationClaimController {

    private final ReleaseController release;
    private final ReservationStore reservations;
    private final StringRedisTemplate redis;

    public ReservationClaimController(ReleaseController release, ReservationStore reservations,
                                       StringRedisTemplate redis) {
        this.release = release;
        this.reservations = reservations;
        this.redis = redis;
    }

    public record ClaimRequest(String sku, String ticketId) {}

    @PostMapping("/reservations/claim")
    public ResponseEntity<?> claim(@RequestBody ClaimRequest request,
                                   @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "X-Client-Id header required"));
        }
        if (!release.isAdmitted(request.sku(), request.ticketId())) {
            return ResponseEntity.status(403).body(Map.of("error", "ticket is not admitted"));
        }
        String owner = redis.opsForValue().get("ticket-owner:" + request.sku() + ":" + request.ticketId());
        if (owner == null || !owner.equals(clientId)) {
            return ResponseEntity.status(403).body(Map.of("error", "this ticket was not issued to you"));
        }

        ClaimResult result = reservations.claim(request.sku(), Duration.ofMinutes(5));
        if (!result.allowed()) {
            return ResponseEntity.status(409).body(Map.of("error", "sold out"));
        }
        // Bind the reservation to the same identity that owned the ticket,
        // so checkout can verify the caller redeeming it is the caller it
        // was granted to -- not just that the token happens to be valid.
        redis.opsForValue().set("reservation-owner:" + request.sku() + ":" + result.reservationToken(),
                clientId, Duration.ofMinutes(30));

        return ResponseEntity.ok(Map.of(
                "sku", request.sku(),
                "reservationToken", result.reservationToken(),
                "remaining", result.remaining()
        ));
    }
}
