package com.dogdaycare.repository;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByCustomer(User customer);
    List<Booking> findByDate(LocalDate date);
    List<Booking> findByDateBetween(LocalDate start, LocalDate end);

    // Week-scoped for a user
    List<Booking> findByCustomerAndDateBetween(User customer, LocalDate startInclusive, LocalDate endInclusive);

    // Filter by service type contains (case-insensitive) within a date range
    List<Booking> findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetween(
            User customer, String serviceTypeLike, LocalDate startInclusive, LocalDate endInclusive);
}
