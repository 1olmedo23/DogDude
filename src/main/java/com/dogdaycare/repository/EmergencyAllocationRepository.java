package com.dogdaycare.repository;

import com.dogdaycare.model.EmergencyAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface EmergencyAllocationRepository extends JpaRepository<EmergencyAllocation, Long> {
    long countByDate(LocalDate date);

    void deleteByBookingId(Long bookingId); // (optional) free a spot if an emergency booking is cancelled.
}
