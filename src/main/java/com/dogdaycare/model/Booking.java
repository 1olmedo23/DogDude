package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "booking")
@Getter
@Setter
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User customer;

    // Examples:
    // "Daycare (6 AM - 3 PM)", "Daycare (6 AM - 8 PM)", "Boarding"
    @Column(name = "service_type")
    private String serviceType;

    private LocalDate date;
    private LocalTime time;

    // PENDING, APPROVED, CANCELED
    private String status = "APPROVED";

    // --- Discounts / audit / locking ---
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "advance_eligible")
    private boolean advanceEligible;

    @Column(name = "wants_advance_pay")
    private boolean wantsAdvancePay;

    @Column(name = "in_prepay_bundle", nullable = false)
    private boolean inPrepayBundle = false;

    @Column(name = "quoted_rate_at_lock", precision = 10, scale = 2)
    private BigDecimal quotedRateAtLock; // null until bundle is locked

    @Column(name = "bundle_locked_at")
    private OffsetDateTime bundleLockedAt; // null until bundle is locked

    // --- Paid state (day-level) ---
    @Column(name = "paid")
    private boolean paid;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
