package com.rrmadon.flashsale.admission;

import com.rrmadon.flashsale.queue.QueueStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
public class AdmissionController {

    private final QueueStore queue;

    public AdmissionController(QueueStore queue) {
        this.queue = queue;
    }

    public record JoinRequest(String sku) {}

    @PostMapping("/queue/join")
    public Map<String, String> join(@RequestBody JoinRequest request) {
        String ticketId = queue.join(request.sku());
        return Map.of("sku", request.sku(), "ticketId", ticketId);
    }
}
