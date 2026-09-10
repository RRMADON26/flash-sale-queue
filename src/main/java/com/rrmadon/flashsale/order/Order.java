package com.rrmadon.flashsale.order;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reservationToken;
    private String sku;
    private String status;
    private Instant createdAt;

    protected Order() { }

    public Order(String reservationToken, String sku, String status, Instant createdAt) {
        this.reservationToken = reservationToken;
        this.sku = sku;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getReservationToken() { return reservationToken; }
    public String getSku() { return sku; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
