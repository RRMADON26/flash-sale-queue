package com.rrmadon.flashsale.release;

import com.rrmadon.flashsale.order.OrderRepository;
import com.rrmadon.flashsale.reservation.ReservationCleanupService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Defense in depth (Card 24): the whole project puts a lot of trust in
 * claim.lua never having a bug. This doesn't assume that trust is
 * warranted forever -- it cross-checks the three independent sources of
 * truth about one SKU's stock and flags disagreement instead of silently
 * trusting the atomic claim is bug-free.
 *
 * initialStock (the starting count before any sale) is required rather
 * than inferred, since Redis's current stock:{sku} value alone can't tell
 * you what it started at.
 */
@Service
public class ReconciliationService {

    private final StringRedisTemplate redis;
    private final OrderRepository orders;

    public ReconciliationService(StringRedisTemplate redis, OrderRepository orders) {
        this.redis = redis;
        this.orders = orders;
    }

    public record Report(long initialStock, long currentStock, long completedOrders,
                          long activeReservations, long accountedFor, boolean consistent) {}

    /**
     * @param sku the SKU to check
     * @param initialStock how many units this SKU started with
     */
    public Report reconcile(String sku, long initialStock) {
        String currentStockStr = redis.opsForValue().get("stock:" + sku);
        long currentStock = currentStockStr == null ? 0 : Long.parseLong(currentStockStr);

        long completedOrders = orders.findAll().stream()
                .filter(o -> o.getSku().equals(sku))
                .count();

        long activeReservations = countActiveReservations(sku);

        // What's "sold" (an order) plus what's "held" (a live reservation) plus
        // what's "available" (current stock) should equal what we started with.
        // Anything else means a unit is unaccounted for -- lost or double-counted.
        long accountedFor = completedOrders + activeReservations + currentStock;
        boolean consistent = accountedFor == initialStock;

        return new Report(initialStock, currentStock, completedOrders, activeReservations, accountedFor, consistent);
    }

    private long countActiveReservations(String sku) {
        var members = redis.opsForZSet().rangeByScore(ReservationCleanupService.PENDING_KEY,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        if (members == null) return 0;
        return members.stream().filter(m -> m.startsWith(sku + ":")).count();
    }
}
