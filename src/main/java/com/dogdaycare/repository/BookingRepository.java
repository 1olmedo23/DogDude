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

    // Weekly filters (useful for daycare bundle counts)
    List<Booking> findByCustomerAndDateBetween(User customer, LocalDate startInclusive, LocalDate endInclusive);

    List<Booking> findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetween(
            User customer, String serviceTypeLike, LocalDate startInclusive, LocalDate endInclusive);

    List<Booking> findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
            User customer,
            String serviceTypeLike,
            LocalDate startInclusive,
            LocalDate endInclusive,
            String status);

    List<Booking> findByCustomerAndDateBetweenAndStatusNotIgnoreCase(
            User customer,
            LocalDate startDateInclusive,
            LocalDate endDateInclusive,
            String statusToExclude
    );

    List<Booking> findByCustomerAndServiceTypeContainingIgnoreCaseAndDateAndStatusNotIgnoreCase(
            User customer,
            String serviceTypeLike,
            LocalDate date,
            String statusToExclude
    );
}
