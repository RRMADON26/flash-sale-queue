package com.rrmadon.flashsale.checkout;

import com.rrmadon.flashsale.order.Order;
import com.rrmadon.flashsale.release.CheckoutMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CheckoutMetrics metrics;

    public CheckoutController(CheckoutService checkoutService, CheckoutMetrics metrics) {
        this.checkoutService = checkoutService;
        this.metrics = metrics;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CheckoutRequest request,
                                       @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.status(400).body(Map.of("error", "X-Client-Id header required"));
        }
        return checkoutService.checkout(request, clientId)
                .map(order -> {
                    metrics.recordSuccess();
                    return ResponseEntity.status(201).body((Object) toBody(order));
                })
                // An unknown/expired reservation is an expected outcome, not a
                // system error -- see CheckoutMetrics's class doc for why this
                // must not count toward the Release Controller's error rate.
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "unknown or expired reservation")));
    }

    /**
     * A real system failure (DB down, unexpected exception) -- this is what
     * the Release Controller's calibration actually reacts to.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onError(Exception e) {
        metrics.recordError();
        return ResponseEntity.status(500).body(Map.of("error", "checkout failed"));
    }

    private Map<String, Object> toBody(Order order) {
        return Map.of(
                "orderId", order.getId(),
                "sku", order.getSku(),
                "status", order.getStatus()
        );
    }
}
