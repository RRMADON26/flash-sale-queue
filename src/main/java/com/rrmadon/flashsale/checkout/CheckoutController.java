package com.rrmadon.flashsale.checkout;

import com.rrmadon.flashsale.order.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CheckoutRequest request) {
        return checkoutService.checkout(request)
                .map(order -> ResponseEntity.status(201).body((Object) toBody(order)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "unknown or expired reservation")));
    }

    private Map<String, Object> toBody(Order order) {
        return Map.of(
                "orderId", order.getId(),
                "sku", order.getSku(),
                "status", order.getStatus()
        );
    }
}
