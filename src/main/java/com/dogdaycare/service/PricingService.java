package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.math.RoundingMode;

@Service
public class PricingService {

    private final BookingRepository bookingRepository;

    public PricingService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    // Standard daycare prices
    private static final BigDecimal DC_HALF_IMM =
            PricingRules.DAYCARE_SHORT;
    private static final BigDecimal DC_FULL_IMM =
            PricingRules.DAYCARE_LONG;

    // Legacy fallback for an unsupported extended SKU.
    // Preserve until service classification is fully migrated.
    private static final BigDecimal DC_EXT_IMM = bd(80);

    // Weekly prepay prices
    private static final BigDecimal DC_HALF_PREPAY_1_3 =
            PricingRules.SHORT_PREPAY_1_TO_3;
    private static final BigDecimal DC_FULL_PREPAY_1_3 =
            PricingRules.LONG_PREPAY_1_TO_3;

    private static final BigDecimal DC_HALF_PREPAY_4P =
            PricingRules.SHORT_PREPAY_4_PLUS;
    private static final BigDecimal DC_FULL_PREPAY_4P =
            PricingRules.LONG_PREPAY_4_PLUS;

    // Legacy extended SKU fallback
    private static final BigDecimal DC_EXT_PREPAY_1_3 = bd(70);
    private static final BigDecimal DC_EXT_PREPAY_4P = bd(60);

    // After Hours remains separate from the prepay bundle
    private static final BigDecimal DC_AFTER_HOURS =
            PricingRules.AFTER_HOURS;

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    private boolean isDaycare(Booking b) {
        String s = b.getServiceType();
        return s != null && s.toLowerCase().contains("daycare");
    }

    private boolean isBoarding(Booking b) {
        String s = b.getServiceType();
        return s != null && s.toLowerCase().contains("boarding");
    }

    // NEW: detect "Daycare After Hours (6 AM - 11 PM)"
    private boolean isAfterHours(Booking b) {
        String s = b.getServiceType();
        if (s == null) return false;
        String sl = s.toLowerCase();
        return sl.contains("after hours") || s.contains("6 AM - 11 PM");
    }

    private boolean isHalfDay(Booking b) {
        String s = b.getServiceType();
        return s != null && s.contains("6 AM - 3 PM");
    }

    private boolean isExtended(Booking b) {
        String s = b.getServiceType();
        return s != null && s.contains("8 PM"); // treating 6AM–8PM as long/full block
    }

    private boolean isFullDay(Booking b) {
        return isDaycare(b) && isExtended(b); // map 6–8 to the “full-day” band
    }

    private LocalDate weekStart(LocalDate any) {
        return any.with(DayOfWeek.MONDAY);
    }
    private LocalDate weekEnd(LocalDate ws) {
        return ws.plusDays(6);
    }

    private LocalDate priorMonthStart(LocalDate any) {
        LocalDate firstOfThisMonth = any.withDayOfMonth(1);
        LocalDate lastMonth = firstOfThisMonth.minusMonths(1);
        return lastMonth.withDayOfMonth(1);
    }
    private LocalDate priorMonthEnd(LocalDate any) {
        LocalDate pms = priorMonthStart(any);
        return pms.withDayOfMonth(pms.lengthOfMonth());
    }

    /** Monday of the week for a given date (Mon–Sun window). */
    public LocalDate weekStartMonday(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        int shiftBack = (dow.getValue() - DayOfWeek.MONDAY.getValue() + 7) % 7;
        return date.minusDays(shiftBack);
    }

    /** Sunday of the same Mon–Sun week. */
    public LocalDate weekEndSunday(LocalDate date) {
        LocalDate start = weekStartMonday(date);
        return start.plusDays(6);
    }

    /**
     * Compute price for a single booking at evaluation time.
     * For daycare discount: booking must be 24h+ out, daycare, and customer opted-in (wantsAdvancePay).
     */
    public BigDecimal priceFor(Booking b) {
        if (b == null || b.getCustomer() == null) return BigDecimal.ZERO;

        // If we already locked a quote, prefer it (idempotent behavior)
        if (b.getQuotedRateAtLock() != null) return b.getQuotedRateAtLock();

        // NEW: Daycare After Hours is always flat $90 (no discount)
        if (isAfterHours(b)) {
            return DC_AFTER_HOURS.setScale(2, RoundingMode.HALF_UP);
        }

        if (isDaycare(b)) {
            return priceDaycare(b);
        } else if (isBoarding(b)) {
            return priceBoarding(b);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal priceDaycare(Booking b) {
        // If not in advance or not opted in, immediate rates
        boolean qualifies = b.isAdvanceEligible() && b.isWantsAdvancePay();

        if (!qualifies) {
            if (isHalfDay(b)) return DC_HALF_IMM;
            if (isFullDay(b)) return DC_FULL_IMM;
            // If you add “Extended” distinct SKU later: return DC_EXT_IMM
            return DC_FULL_IMM; // default to full for safety
        }

        // Count customer’s prepay daycare bookings that fall in the same Mon–Sun week
        User u = b.getCustomer();
        LocalDate ws = weekStartMonday(b.getDate());
        LocalDate we = weekEndSunday(b.getDate());

        List<Booking> weekDaycare = bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        u, "daycare", ws, we, "CANCELED");


        int qualifyingDogDays =
                PricingRules.weeklyPrepayDogDays(weekDaycare);

        boolean atLeast4 = qualifyingDogDays >= 4;

        if (atLeast4) {
            if (isHalfDay(b)) return DC_HALF_PREPAY_4P;
            if (isFullDay(b)) return DC_FULL_PREPAY_4P;
            return DC_EXT_PREPAY_4P; // if you add extended explicitly later
        } else {
            if (isHalfDay(b)) return DC_HALF_PREPAY_1_3;
            if (isFullDay(b)) return DC_FULL_PREPAY_1_3;
            return DC_EXT_PREPAY_1_3;
        }
    }

    private BigDecimal priceBoarding(Booking b) {
        User customer = b.getCustomer();

        LocalDate previousMonthStart =
                priorMonthStart(b.getDate());

        LocalDate previousMonthEnd =
                priorMonthEnd(b.getDate());

        // All qualifying services from the preceding calendar month
        List<Booking> previousMonthBookings =
                bookingRepository.findByCustomerAndDateBetween(
                        customer,
                        previousMonthStart,
                        previousMonthEnd
                );

        int qualifyingDogDays =
                PricingRules.boardingQualifyingDogDays(
                        previousMonthBookings
                );

        BigDecimal nightlyRate =
                PricingRules.boardingRatePerDog(qualifyingDogDays);

        boolean lastBoardingNight =
                !hasBoardingOnNextDay(customer, b.getDate());

        boolean pickupDayHasEligibleService =
                hasDaycareOnDate(
                        customer,
                        b.getDate().plusDays(1)
                );

        BigDecimal price = nightlyRate;

        if (lastBoardingNight && !pickupDayHasEligibleService) {
            price = price.add(
                    PricingRules.pickupFeePerDog(qualifyingDogDays)
            );
        }

        return price.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Return the daycare price for this booking at a specific tier.
     * @param atLeast4 true => use ≥4 prepay tier, false => use 1–3 prepay tier
     */
    public BigDecimal quoteDaycareAtTier(Booking b, boolean atLeast4) {
        // mirror your priceDaycare() bands: half vs full (6–3 vs 6–8)
        if (atLeast4) {
            if (isHalfDay(b)) return DC_HALF_PREPAY_4P;
            if (isFullDay(b)) return DC_FULL_PREPAY_4P;
            return DC_EXT_PREPAY_4P;
        } else {
            if (isHalfDay(b)) return DC_HALF_PREPAY_1_3;
            if (isFullDay(b)) return DC_FULL_PREPAY_1_3;
            return DC_EXT_PREPAY_1_3;
        }
    }

    private boolean hasBoardingOnNextDay(User u, LocalDate date) {
        return !bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateAndStatusNotIgnoreCase(
                        u, "boarding", date.plusDays(1), "CANCELED")
                .isEmpty();
    }

    private boolean hasDaycareOnDate(User customer, LocalDate date) {
        return bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateAndStatusNotIgnoreCase(
                        customer,
                        "daycare",
                        date,
                        "CANCELED"
                )
                .stream()
                .anyMatch(b ->
                        PricingRules.waivesPickupFee(
                                b.getServiceType()
                        )
                );
    }

    public BigDecimal previewDaycarePrice(User u,
                                          LocalDate date,
                                          String serviceType,
                                          boolean advanceEligible,
                                          boolean wantsAdvancePay) {
        // NEW: After Hours is always flat $90 (no discounts)
        if (serviceType != null) {
            String sl = serviceType.toLowerCase();
            if (sl.contains("after hours") || serviceType.contains("6 AM - 11 PM")) {
                return DC_AFTER_HOURS.setScale(2, RoundingMode.HALF_UP);
            }
        }

        // If not qualifying for prepay, use immediate bands
        boolean qualifies = advanceEligible && wantsAdvancePay;
        Booking temp = new Booking();
        temp.setServiceType(serviceType);

        if (!qualifies) {
            // mirror your immediate daycare mapping
            if (isHalfDay(temp)) return DC_HALF_IMM;
            if (isFullDay(temp)) return DC_FULL_IMM;
            return DC_EXT_IMM;
        }

        // Count existing eligible daycare in the same week
        LocalDate ws = weekStartMonday(date);
        LocalDate we = weekEndSunday(date);
        List<Booking> weekDaycare = bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        u, "daycare", ws, we, "CANCELED");

        int existingEligible =
                PricingRules.weeklyPrepayDogDays(weekDaycare);

        boolean atLeast4 = (existingEligible + 1) >= 4; // include THIS booking
        return quoteDaycareAtTier(temp, atLeast4);
    }

    public BigDecimal previewBoardingStayPrice(User u, LocalDate startDate, int nights) {
        if (u == null || startDate == null) return BigDecimal.ZERO;

        if (nights < 1) nights = 1;
        nights = Math.max(1, Math.min(30, nights));

        // Determine the tier from previous-month qualifying activity
        LocalDate pms = priorMonthStart(startDate);
        LocalDate pme = priorMonthEnd(startDate);

        List<Booking> previousMonthBookings =
                bookingRepository.findByCustomerAndDateBetween(
                        u, pms, pme
                );

        int qualifyingDogDays =
                PricingRules.boardingQualifyingDogDays(
                        previousMonthBookings
                );

        BigDecimal nightly =
                PricingRules.boardingRatePerDog(qualifyingDogDays);

        // The "last night" is the final booking date in the stay
        LocalDate lastNightDate = startDate.plusDays(nights - 1);

        // Pickup day is the day after the last night
        boolean pickupDayHasDaycare = hasDaycareOnDate(u, lastNightDate.plusDays(1));

        // Total = nightly * nights, plus pickup fee once on last night (nightly/2), unless pickup day has daycare
        BigDecimal total = nightly.multiply(BigDecimal.valueOf(nights));

        if (!pickupDayHasDaycare) {
            total = total.add(nightly.multiply(BigDecimal.valueOf(0.5)));
        }

        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the booking's base TOTAL for all dogs.
     *
     * Unpaid bookings use current pricing rules.
     * Paid bookings use their saved finalized total.
     *
     * Manual admin adjustments are excluded here.
     */
    public BigDecimal baseTotalFor(Booking booking) {
        if (booking == null || booking.getCustomer() == null) {
            return BigDecimal.ZERO.setScale(2);
        }

        // Preserve finalized paid amounts.
        if (booking.isPaid()
                && booking.getQuotedRateAtLock() != null) {
            return booking.getQuotedRateAtLock()
                    .setScale(2, RoundingMode.HALF_UP);
        }

        int dogs = booking.getDogCount() == null
                ? 1
                : Math.max(1, booking.getDogCount());

        BigDecimal perDog;

        if (isAfterHours(booking)) {
            perDog = PricingRules.AFTER_HOURS;

        } else if (isBoarding(booking)) {
            perDog = priceBoarding(booking);

        } else if (isDaycare(booking)) {
            perDog = priceDaycare(booking);

        } else {
            perDog = BigDecimal.ZERO;
        }

        return perDog
                .multiply(BigDecimal.valueOf(dogs))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the total amount including the admin
     * adjustment, applied once per booking.
     */
    public BigDecimal finalTotalFor(Booking booking) {
        BigDecimal base = baseTotalFor(booking);

        if (booking == null) {
            return base;
        }

        BigDecimal adjustment =
                booking.getManualAdjustmentAmount() == null
                        ? BigDecimal.ZERO
                        : booking.getManualAdjustmentAmount();

        return base.add(adjustment)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
