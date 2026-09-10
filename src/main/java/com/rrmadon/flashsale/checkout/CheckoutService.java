package com.rrmadon.flashsale.checkout;

import com.rrmadon.flashsale.order.Order;
import com.rrmadon.flashsale.order.OrderRepository;
import com.rrmadon.flashsale.reservation.ReservationCleanupService;
import com.rrmadon.flashsale.reservation.ReservationStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class CheckoutService {

    private final StringRedisTemplate redis;
    private final ReservationStore reservationStore;
    private final OrderRepository orders;
    private final Clock clock;

    public CheckoutService(StringRedisTemplate redis, ReservationStore reservationStore,
                            OrderRepository orders, Clock clock) {
        this.redis = redis;
        this.reservationStore = reservationStore;
        this.orders = orders;
        this.clock = clock;
    }

    public Optional<Order> checkout(CheckoutRequest request, String clientId) {
        String member = request.sku() + ":" + request.reservationToken();
        Double score = redis.opsForZSet().score(ReservationCleanupService.PENDING_KEY, member);

        if (score == null) {
            // Unknown, already-completed, or already-expired-and-swept token.
            // Stub payment capture (architecture doc §09: real gateway
            // integration is explicitly out of scope) -- this is where a
            // real charge attempt would go.
            return Optional.empty();
        }

        // Card 26: the token alone is not enough -- it must belong to the
        // caller redeeming it. Treated the same as "unknown reservation"
        // (404, not 403) so a probing caller can't distinguish "wrong owner"
        // from "doesn't exist" by response code.
        String owner = redis.opsForValue().get("reservation-owner:" + request.sku() + ":" + request.reservationToken());
        if (owner == null || !owner.equals(clientId)) {
            return Optional.empty();
        }

        // Order persisted BEFORE the reservation is released, deliberately.
        // If save() throws, the reservation is still intact in Redis: the
        // client's retry (via the same Idempotency-Key, or a fresh checkout
        // call once whatever broke is fixed) can still complete it. Doing
        // this the other way round -- release first, save second -- would
        // mean a save failure permanently strands the unit: not sold, not
        // refunded, not retryable. Found by running this against a real
        // database and hitting a real SQL failure, not assumed correct.
        Order order = orders.save(new Order(request.reservationToken(), request.sku(),
                "captured", Instant.now(clock)));
        reservationStore.release(request.sku(), request.reservationToken());
        return Optional.of(order);
    }
}
