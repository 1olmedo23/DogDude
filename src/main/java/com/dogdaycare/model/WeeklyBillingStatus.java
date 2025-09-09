package com.dogdaycare.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "weekly_billing_status",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_week", columnNames = {"user_id","week_start"}))
public class WeeklyBillingStatus {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false) // keep aligned with your User entity
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart; // Monday (ISO week)

    @Column(name = "prepay_locked_at")
    private LocalDateTime prepayLockedAt;

    @Column(name = "is_paid", nullable = false)
    private boolean paid = false;

    public WeeklyBillingStatus() {}

    public WeeklyBillingStatus(User user, LocalDate weekStart) {
        this.user = user;
        this.weekStart = weekStart;
    }

    // getters/setters
    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public LocalDate getWeekStart() { return weekStart; }
    public void setWeekStart(LocalDate weekStart) { this.weekStart = weekStart; }
    public LocalDateTime getPrepayLockedAt() { return prepayLockedAt; }
    public void setPrepayLockedAt(LocalDateTime prepayLockedAt) { this.prepayLockedAt = prepayLockedAt; }
    public boolean isPaid() { return paid; }
    public void setPaid(boolean paid) { this.paid = paid; }
}
