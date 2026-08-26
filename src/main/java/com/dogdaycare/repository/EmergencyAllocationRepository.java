package com.dogdaycare.repository;

import com.dogdaycare.model.EmergencyAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyAllocationRepository extends JpaRepository<EmergencyAllocation, Long> {

    void deleteByBookingId(Long bookingId);
}