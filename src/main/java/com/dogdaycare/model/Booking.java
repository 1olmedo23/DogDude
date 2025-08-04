package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
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

    private String serviceType; // Daycare AM, Daycare Full, Boarding
    private LocalDate date;
    private LocalTime time;

    private String status = "APPROVED"; // PENDING, APPROVED, CANCELED
}