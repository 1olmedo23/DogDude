package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EmergencyAllocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingLimitService {

    private final BookingRepository bookingRepository;
    private final EmergencyAllocationRepository emergencyRepo;

    // Daily caps (ALL included in the 70 total cap)
    private static final int CAP_TOTAL = 70;       // includes emergency usage
    private static final int CAP_DAYCARE = 40;     // 6-3 + 6-8 combined or "Daycare AM/Full"
    private static final int CAP_BOARDING = 20;    // boarding only
    private static final int CAP_EMERGENCY = 10;   // admin-only, counts toward total

    /** Service classifiers (label-based, robust to your values). */
    public static boolean isDaycare(String serviceLabel) {
        if (serviceLabel == null) return false;
        String s = serviceLabel.toLowerCase();
        // supports "Daycare (6 AM - 3 PM)", "Daycare (6 AM - 8 PM)", "Daycare AM", "Daycare Full", etc.
        return s.contains("daycare");
    }

    public static boolean isBoarding(String serviceLabel) {
        if (serviceLabel == null) return false;
        String s = serviceLabel.toLowerCase();
        return s.contains("boarding");
    }

    /** Daily counts ignoring CANCELED bookings. */
    public DailyCount snapshot(LocalDate date) {
        List<Booking> all = bookingRepository.findByDate(date);
        int total = 0;
        int daycare = 0;
        int boarding = 0;

        for (Booking b : all) {
            String status = b.getStatus() == null ? "" : b.getStatus();
            if ("CANCELED".equalsIgnoreCase(status)) continue;

            total++;
            if (isDaycare(b.getServiceType())) daycare++;
            else if (isBoarding(b.getServiceType())) boarding++;
        }

        long emergencyUsed = emergencyRepo.countByDate(date);
        // emergencyUsed also counts toward total; we track it separately for admin UX.

        return new DailyCount(total, daycare, boarding, (int) emergencyUsed);
    }

    /** Can a CUSTOMER place this booking under normal capacity? */
    public boolean canCustomerBook(LocalDate date, String serviceType) {
        DailyCount c = snapshot(date);

        // Enforce total cap first
        if (c.total >= CAP_TOTAL) return false;

        if (isDaycare(serviceType)) {
            return c.daycare < CAP_DAYCARE;
        } else if (isBoarding(serviceType)) {
            return c.boarding < CAP_BOARDING;
        } else {
            // Unknown services fall back to total-only cap
            return c.total < CAP_TOTAL;
        }
    }

    /** Are emergency spots still available for this date (and total not exceeded)? */
    public boolean canUseEmergency(LocalDate date) {
        DailyCount c = snapshot(date);
        if (c.total >= CAP_TOTAL) return false; // total must remain <= 70
        return c.emergencyUsed < CAP_EMERGENCY;
    }

    /**
     * Should admin use an emergency spot?
     * True when normal capacity is full for the chosen service (or total is at cap, which also blocks).
     * If total is already at cap, admin can't book at all.
     */
    public boolean shouldUseEmergency(LocalDate date, String serviceType) {
        DailyCount c = snapshot(date);

        // If total already at cap, even emergency is not allowed.
        if (c.total >= CAP_TOTAL) return false;

        // Emergency is for when normal capacity for that service is reached.
        if (isDaycare(serviceType)) {
            return c.daycare >= CAP_DAYCARE;
        } else if (isBoarding(serviceType)) {
            return c.boarding >= CAP_BOARDING;
        } else {
            // For any unknown service, default to only total cap (no emergency path)
            return false;
        }
    }

    /** Convenience record for UI. */
    @lombok.Value
    public static class DailyCount {
        int total;
        int daycare;
        int boarding;
        int emergencyUsed;

        public int emergencyRemaining() { return Math.max(0, CAP_EMERGENCY - emergencyUsed); }
        public int totalCap() { return CAP_TOTAL; }
        public int daycareCap() { return CAP_DAYCARE; }
        public int boardingCap() { return CAP_BOARDING; }
        public int emergencyCap() { return CAP_EMERGENCY; }
    }
}
