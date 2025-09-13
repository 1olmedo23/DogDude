package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Getter
@Setter
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User customer;

    private String serviceType; // Daycare..., Boarding
    private LocalDate date;
    private LocalTime time;

    private String status = "APPROVED"; // PENDING, APPROVED, CANCELED

    // --- discounts / audit / prepay flags ---
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "advance_eligible")
    private boolean advanceEligible;

    @Column(name = "wants_advance_pay")
    private boolean wantsAdvancePay;

    @Column(name = "in_prepay_bundle")
    private boolean inPrepayBundle;

    @Column(name = "quoted_rate_at_lock", precision = 10, scale = 2)
    private BigDecimal quotedRateAtLock;

    // --- NEW: day-level paid tracking ---
    @Column(name = "paid")
    private boolean paid;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}