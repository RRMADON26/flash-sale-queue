package com.rrmadon.flashsale.queue;

import com.rrmadon.flashsale.release.ReleaseController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class QueueController {

    private final QueueStore queue;
    private final ReleaseController release;

    public QueueController(QueueStore queue, ReleaseController release) {
        this.queue = queue;
        this.release = release;
    }

    @GetMapping("/queue/{sku}/{ticketId}/position")
    public ResponseEntity<Map<String, Object>> position(@PathVariable String sku, @PathVariable String ticketId) {
        return queue.positionOf(sku, ticketId)
                .map(rank -> ResponseEntity.ok((Map<String, Object>) Map.<String, Object>of(
                        "sku", sku, "ticketId", ticketId, "position", rank)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "ticket not found -- never joined, or already admitted")));
    }

    @GetMapping("/queue/{sku}/{ticketId}/status")
    public Map<String, Object> status(@PathVariable String sku, @PathVariable String ticketId) {
        if (release.isAdmitted(sku, ticketId)) {
            return Map.of("sku", sku, "ticketId", ticketId, "status", "admitted");
        }
        return queue.positionOf(sku, ticketId)
                .<Map<String, Object>>map(rank -> Map.of("sku", sku, "ticketId", ticketId, "status", "queued", "position", rank))
                .orElse(Map.of("sku", sku, "ticketId", ticketId, "status", "unknown"));
    }
}
