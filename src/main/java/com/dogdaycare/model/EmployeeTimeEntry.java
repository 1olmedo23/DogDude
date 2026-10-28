
package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "employee_time_entry")
public class EmployeeTimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private User employee;

    @Column(name = "clock_in", nullable = false)
    private LocalDateTime clockIn;

    @Column(name = "clock_out")
    private LocalDateTime clockOut;

    // Admin-adjusted timestamps.
    // Original clock-in and clock-out values remain preserved.

    @Column(name = "adjusted_clock_in")
    private LocalDateTime adjustedClockIn;

    @Column(name = "adjusted_clock_out")
    private LocalDateTime adjustedClockOut;
}