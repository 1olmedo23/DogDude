package com.dogdaycare.service;

import com.dogdaycare.dto.EmergencyCounts;
import com.dogdaycare.model.Booking;
import com.dogdaycare.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingLimitService {

    private final BookingRepository bookingRepository;

    // Configurable caps (application.properties)
    @Value("${booking.cap.total:70}")
    private int totalCap;

    @Value("${booking.cap.daycare:40}")
    private int daycareCap;

    @Value("${booking.cap.boarding:20}")
    private int boardingCap;

    @Value("${booking.cap.emergency:10}")
    private int emergencyCap;

    /** Snapshot counts + caps for a given date. */
    public EmergencyCounts snapshot(LocalDate date) {
        List<Booking> bookings = bookingRepository.findByDate(date);

        int daycare = (int) bookings.stream()
                .filter(b -> !isCanceled(b) && isDaycare(b.getServiceType()))
                .count();

        int boarding = (int) bookings.stream()
                .filter(b -> !isCanceled(b) && isBoarding(b.getServiceType()))
                .count();

        int total = daycare + boarding;

        // Derive emergency usage from total vs normal caps so cancellations instantly free capacity.
        int normalCap = daycareCap + boardingCap;
        int emergencyUsed = Math.max(0, Math.min(emergencyCap, total - normalCap));

        return new EmergencyCounts(
                date,
                total,
                daycare,
                boarding,
                emergencyUsed,
                totalCap,
                daycareCap,
                boardingCap,
                emergencyCap
        );
    }

    /** Can a regular customer book this service on this date (without using emergency)? */
    public boolean canCustomerBook(LocalDate date, String serviceType) {
        EmergencyCounts c = snapshot(date);

        // Daily hard cap first
        if (c.getTotal() >= c.totalCap()) return false;

        if (isDaycare(serviceType)) {
            return c.getDaycare() < c.daycareCap();
        } else if (isBoarding(serviceType)) {
            return c.getBoarding() < c.boardingCap();
        }
        // Unknown service: be conservative
        return false;
    }

    /** Should an admin emergency spot be used for the given service? */
    public boolean shouldUseEmergency(LocalDate date, String serviceType) {
        EmergencyCounts c = snapshot(date);

        boolean totalOk = c.getTotal() < c.totalCap();
        boolean emergencyAvailable = c.emergencyRemaining() > 0;

        if (isDaycare(serviceType)) {
            return (c.getDaycare() >= c.daycareCap()) && totalOk && emergencyAvailable;
        } else if (isBoarding(serviceType)) {
            return (c.getBoarding() >= c.boardingCap()) && totalOk && emergencyAvailable;
        }
        return false;
    }

    /** Can we consume an emergency spot (regardless of service)? */
    public boolean canUseEmergency(LocalDate date) {
        EmergencyCounts c = snapshot(date);
        return c.getTotal() < c.totalCap() && c.emergencyRemaining() > 0;
    }

    // ---------- helpers ----------

    private boolean isCanceled(Booking b) {
        return "CANCELED".equalsIgnoreCase(b.getStatus());
    }

    public static boolean isDaycare(String st) {
        return st != null && st.toLowerCase().contains("daycare");
    }

    public static boolean isBoarding(String st) {
        return st != null && st.toLowerCase().contains("boarding");
    }
}
