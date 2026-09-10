package com.rrmadon.flashsale.admission;

import com.rrmadon.flashsale.queue.QueueStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * The one endpoint every opening-second request touches. Protected by
 * Palang's rate-limit filter -- which, per the app-wide config, is
 * currently fail-closed, not the fail-open the architecture doc's §08
 * table calls for here. Palang's filter is global-per-app, not
 * per-route, in v0.4.0; a real per-route fix belongs upstream (tracked
 * in Palang's own CONTRIBUTING.md), not duplicated here as throwaway
 * code. Documented as a known trade-off, not silently resolved.
 *
 * Also requires an Idempotency-Key, since palang.idempotency.require-key
 * is (also) app-wide -- discovered, not designed for, while manually
 * testing this endpoint. Kept rather than worked around: a client whose
 * join request times out and retries would otherwise risk being issued
 * two tickets for one logical join attempt, the same duplicate-request
 * problem checkout guards against, just lower-stakes. Callers should
 * generate one key per join attempt and reuse it only on retry.
 *
 * X-Client-Id is a minimal identity binding, not real authentication --
 * there is no login, no session, nothing verifying the header is who it
 * claims to be. It closes the specific gap Card 26 was opened for (a
 * stolen reservation token alone is no longer sufficient to redeem it
 * at checkout), not the general problem of "who is this caller,
 * really" -- a real deployment needs actual auth in front of this.
 */
@RestController
public class AdmissionController {

    private final QueueStore queue;
    private final StringRedisTemplate redis;

    public AdmissionController(QueueStore queue, StringRedisTemplate redis) {
        this.queue = queue;
        this.redis = redis;
    }

    public record JoinRequest(String sku) {}

    /**
     * Card 26: binds the ticket to whoever joined, via a client-supplied
     * identifier -- not real user auth (out of scope, see class doc below),
     * but enough that a ticket's admission and eventual reservation can't
     * be redeemed by a different caller than the one who joined, which is
     * what actually matters for correctness here.
     */
    @PostMapping("/queue/join")
    public ResponseEntity<?> join(@RequestBody JoinRequest request, @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "X-Client-Id header required"));
        }
        String ticketId = queue.join(request.sku());
        redis.opsForValue().set("ticket-owner:" + request.sku() + ":" + ticketId, clientId, Duration.ofHours(1));
        return ResponseEntity.ok(Map.of("sku", request.sku(), "ticketId", ticketId));
    }
}
