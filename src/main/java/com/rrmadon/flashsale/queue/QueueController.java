package com.rrmadon.flashsale.queue;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class QueueController {

    private final QueueStore queue;

    public QueueController(QueueStore queue) {
        this.queue = queue;
    }

    @GetMapping("/queue/{sku}/{ticketId}/position")
    public ResponseEntity<Map<String, Object>> position(@PathVariable String sku, @PathVariable String ticketId) {
        return queue.positionOf(sku, ticketId)
                .map(rank -> ResponseEntity.ok((Map<String, Object>) Map.<String, Object>of(
                        "sku", sku, "ticketId", ticketId, "position", rank)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "ticket not found -- never joined, or already admitted")));
    }
}
