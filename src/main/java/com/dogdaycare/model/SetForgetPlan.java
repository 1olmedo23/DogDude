package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "set_forget_plan")
@Getter
@Setter
public class SetForgetPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // One plan belongs to one user (customer)
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User customer;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "wants_advance_pay", nullable = false)
    private boolean wantsAdvancePay = false;

    @Column(name = "dog_count", nullable = false)
    private Integer dogCount = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SetForgetRule> rules = new ArrayList<>();
}