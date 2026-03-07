package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

@Entity
@Table(name = "set_forget_rule")
@Getter
@Setter
public class SetForgetRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id")
    private SetForgetPlan plan;

    // 1=Monday ... 7=Sunday (java.time.DayOfWeek.getValue())
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "dropoff_time", nullable = false)
    private LocalTime dropoffTime;

    @Column(nullable = false)
    private boolean active = true;
}