package com.rrmadon.flashsale.reservation;

/**
 * The outcome of attempting to claim one unit of stock.
 *
 * @param allowed true if a unit was successfully reserved
 * @param remaining stock left after this claim, only meaningful when allowed
 * @param reservationToken the caller-supplied unguessable token identifying
 *                         this reservation, echoed back for convenience
 * @param sku the SKU this reservation is for, needed to release() it later
 */
public record ClaimResult(boolean allowed, long remaining, String reservationToken, String sku) {

    public static ClaimResult rejected() {
        return new ClaimResult(false, 0, null, null);
    }
}
