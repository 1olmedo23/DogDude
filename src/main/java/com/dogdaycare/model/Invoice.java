package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(
        name = "invoice",
        uniqueConstraints = @UniqueConstraint(columnNames = {"customer_email", "week_start"})
)
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "customer_name", nullable = false)
    private String customerName; // captured at creation time for display stability

    @Column(name = "dog_name")
    private String dogName; // best-effort from latest evaluation

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart; // Monday of the week

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;   // Sunday of the week

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;   // total owed for the week (non-canceled)

    @Column(name = "paid", nullable = false)
    private boolean paid = false;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
