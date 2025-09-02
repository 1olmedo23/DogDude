package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity
@Table(name = "emergency_allocation",
        indexes = {
                @Index(name = "idx_emergency_allocation_date", columnList = "date")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The service date this emergency slot was used for (local business date).
     */
    @Column(nullable = false)
    private LocalDate date;

    /**
     * Optional: link the booking using this emergency spot for cleanup/reporting.
     */
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
