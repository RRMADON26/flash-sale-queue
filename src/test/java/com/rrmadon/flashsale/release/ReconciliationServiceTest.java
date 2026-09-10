package com.rrmadon.flashsale.release;

import com.rrmadon.flashsale.checkout.CheckoutRequest;
import com.rrmadon.flashsale.checkout.CheckoutService;
import com.rrmadon.flashsale.reservation.ClaimResult;
import com.rrmadon.flashsale.reservation.ReservationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReconciliationServiceTest {

    @Autowired private StringRedisTemplate redis;
    @Autowired private ReservationStore reservations;
    @Autowired private CheckoutService checkout;
    @Autowired private ReconciliationService reconciliation;

    @Test
    void reportsConsistentWhenNothingHasGoneWrong() {
        String sku = "recon-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, "10");

        // 3 completed sales, 2 still-held reservations, 5 untouched -- 10 total.
        for (int i = 0; i < 3; i++) {
            ClaimResult claim = reservations.claim(sku, Duration.ofMinutes(5));
            redis.opsForValue().set("reservation-owner:" + sku + ":" + claim.reservationToken(), "recon-client");
            checkout.checkout(new CheckoutRequest(sku, claim.reservationToken()), "recon-client");
        }
        for (int i = 0; i < 2; i++) {
            reservations.claim(sku, Duration.ofMinutes(5));
        }

        ReconciliationService.Report report = reconciliation.reconcile(sku, 10);

        assertThat(report.completedOrders()).isEqualTo(3);
        assertThat(report.activeReservations()).isEqualTo(2);
        assertThat(report.currentStock()).isEqualTo(5);
        assertThat(report.consistent())
                .as("3 sold + 2 held + 5 available == 10 started -- must reconcile cleanly")
                .isTrue();
    }

    @Test
    void catchesDriftFromStockCorruptedOutsideTheAtomicClaim() {
        String sku = "recon-drift-" + UUID.randomUUID();
        redis.opsForValue().set("stock:" + sku, "10");

        reservations.claim(sku, Duration.ofMinutes(5)); // 1 held, 9 remaining -- correct so far

        // Simulate a bug or an operator mistake: something writes stock
        // directly, bypassing claim.lua entirely. This is exactly the kind
        // of drift the atomic claim is trusted never to allow through its
        // own path, but this job doesn't assume that trust is infinite.
        redis.opsForValue().set("stock:" + sku, "3");

        ReconciliationService.Report report = reconciliation.reconcile(sku, 10);

        assertThat(report.consistent())
                .as("1 held + 3 available == 4, not 10 -- drift must be caught, not silently reported clean")
                .isFalse();
    }
}
