package com.rrmadon.flashsale.checkout;

/** What a client presents to redeem a reservation. */
public record CheckoutRequest(String sku, String reservationToken) {
}
