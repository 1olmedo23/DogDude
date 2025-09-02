package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;

@Service
@RequiredArgsConstructor
public class CancelPolicyService {

    private static final int BOARDING_CANCEL_HOURS = 72;

    public boolean canCustomerCancel(Booking booking, Clock clock) {
        // Daycare is always OK
        if (!BookingLimitService.isBoarding(booking.getServiceType())) return true;

        LocalDate date = booking.getDate();
        if (date == null) return true; // be permissive if missing data

        // If time not provided, assume start-of-day for a conservative check
        LocalTime time = booking.getTime() != null ? booking.getTime() : LocalTime.of(0, 0);
        LocalDateTime start = LocalDateTime.of(date, time);
        LocalDateTime now = LocalDateTime.now(clock);

        Duration diff = Duration.between(now, start);
        return diff.toHours() >= BOARDING_CANCEL_HOURS;
    }

    public String policyMessage(Booking booking) {
        if (BookingLimitService.isBoarding(booking.getServiceType())) {
            return "Boarding cancellations must be made at least 72 hours in advance.";
        }
        return "You can cancel this booking.";
    }
}
