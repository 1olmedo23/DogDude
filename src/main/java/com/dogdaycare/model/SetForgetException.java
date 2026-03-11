package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "set_forget_exception")
@Getter
@Setter
public class SetForgetException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id")
    private SetForgetPlan plan;

    @Column(name = "exception_date", nullable = false)
    private LocalDate exceptionDate;

    @Column(nullable = false, length = 50)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}