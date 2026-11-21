package com.dogdaycare.service;

import com.dogdaycare.model.Booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Collection;

public final class PricingRules {

    private PricingRules() {
        // Utility class; no instances.
    }

    // Standard daycare rates, per dog
    public static final BigDecimal DAYCARE_SHORT =
            new BigDecimal("50.00");
    public static final BigDecimal DAYCARE_LONG =
            new BigDecimal("60.00");
    public static final BigDecimal AFTER_HOURS =
            new BigDecimal("75.00");

    // Weekly prepaid daycare rates, per dog
    public static final BigDecimal SHORT_PREPAY_1_TO_3 =
            new BigDecimal("45.00");
    public static final BigDecimal SHORT_PREPAY_4_PLUS =
            new BigDecimal("40.00");
    public static final BigDecimal LONG_PREPAY_1_TO_3 =
            new BigDecimal("50.00");
    public static final BigDecimal LONG_PREPAY_4_PLUS =
            new BigDecimal("45.00");

    // Boarding rates, per dog per night
    public static final BigDecimal BOARDING_STANDARD =
            new BigDecimal("75.00");
    public static final BigDecimal BOARDING_4_PLUS =
            new BigDecimal("70.00");
    public static final BigDecimal BOARDING_12_PLUS =
            new BigDecimal("60.00");

    public static final LocalTime PREPAY_CUTOFF =
            LocalTime.of(2, 0);

    public static final String DAYCARE_SHORT_SERVICE =
            "Daycare (6 AM - 3 PM)";
    public static final String DAYCARE_LONG_SERVICE =
            "Daycare (6 AM - 8 PM)";
    public static final String AFTER_HOURS_SERVICE =
            "Daycare After Hours (6 AM - 11 PM)";
    public static final String BOARDING_SERVICE =
            "Boarding";

    public static boolean isStandardDaycare(String service) {
        return DAYCARE_SHORT_SERVICE.equalsIgnoreCase(service)
                || DAYCARE_LONG_SERVICE.equalsIgnoreCase(service);
    }

    public static boolean isAfterHours(String service) {
        return AFTER_HOURS_SERVICE.equalsIgnoreCase(service);
    }

    public static boolean isBoarding(String service) {
        return BOARDING_SERVICE.equalsIgnoreCase(service);
    }

    public static boolean isActive(Booking booking) {
        return booking != null
                && !"CANCELED".equalsIgnoreCase(
                booking.getStatus());
    }

    public static int dogCount(Booking booking) {
        if (booking.getDogCount() == null) {
            return 1;
        }
        return Math.max(1, booking.getDogCount());
    }

    // Eligibility cutoff: before 2 AM on service date.
    public static boolean isAdvanceEligible(
            LocalDate serviceDate, Clock clock) {

        if (serviceDate == null || clock == null) {
            return false;
        }

        ZonedDateTime cutoff = serviceDate
                .atTime(PREPAY_CUTOFF)
                .atZone(clock.getZone());

        return ZonedDateTime.now(clock).isBefore(cutoff);
    }

    // Counts only opted-in standard daycare bookings.
    // Each dog counts as one qualifying dog-day.
    public static int weeklyPrepayDogDays(
            Collection<Booking> weekBookings) {

        if (weekBookings == null) {
            return 0;
        }

        return weekBookings.stream()
                .filter(PricingRules::isActive)
                .filter(b -> isStandardDaycare(
                        b.getServiceType()))
                .filter(Booking::isWantsAdvancePay)
                .filter(Booking::isAdvanceEligible)
                .mapToInt(PricingRules::dogCount)
                .sum();
    }

    // Counts all active daycare and boarding activity.
    // Caller supplies bookings from the prior calendar month.
    public static int boardingQualifyingDogDays(
            Collection<Booking> priorMonthBookings) {

        if (priorMonthBookings == null) {
            return 0;
        }

        return priorMonthBookings.stream()
                .filter(PricingRules::isActive)
                .filter(b -> isStandardDaycare(
                        b.getServiceType())
                        || isAfterHours(b.getServiceType())
                        || isBoarding(b.getServiceType()))
                .mapToInt(PricingRules::dogCount)
                .sum();
    }

    public static BigDecimal daycareRatePerDog(
            String service, boolean prepaid, int qualifyingDogDays) {

        if (isAfterHours(service)) {
            return AFTER_HOURS;
        }

        if (DAYCARE_SHORT_SERVICE.equalsIgnoreCase(service)) {
            if (!prepaid) return DAYCARE_SHORT;
            return qualifyingDogDays >= 4
                    ? SHORT_PREPAY_4_PLUS
                    : SHORT_PREPAY_1_TO_3;
        }

        if (DAYCARE_LONG_SERVICE.equalsIgnoreCase(service)) {
            if (!prepaid) return DAYCARE_LONG;
            return qualifyingDogDays >= 4
                    ? LONG_PREPAY_4_PLUS
                    : LONG_PREPAY_1_TO_3;
        }

        throw new IllegalArgumentException(
                "Unknown daycare service: " + service);
    }

    public static BigDecimal boardingRatePerDog(
            int qualifyingDogDays) {

        if (qualifyingDogDays >= 12) {
            return BOARDING_12_PLUS;
        }
        if (qualifyingDogDays >= 4) {
            return BOARDING_4_PLUS;
        }
        return BOARDING_STANDARD;
    }

    public static BigDecimal pickupFeePerDog(
            int qualifyingDogDays) {

        return boardingRatePerDog(qualifyingDogDays)
                .divide(new BigDecimal("2"));
    }

    /**
     * Standard daycare services may be booked on
     * a boarding pickup day. After Hours may not.
     */
    public static boolean canBookOnPickupDay(String serviceType) {
        return isStandardDaycare(serviceType);
    }

    /**
     * An active, allowed daycare booking on the pickup
     * date removes the boarding pickup charge.
     */
    public static boolean waivesPickupFee(String serviceType) {
        return canBookOnPickupDay(serviceType);
    }
}