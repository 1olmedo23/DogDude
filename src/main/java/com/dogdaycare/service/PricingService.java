package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

@Service
public class PricingService {

    private final BookingRepository bookingRepository;

    public PricingService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    // --- Base immediate rates ---
    private static final BigDecimal DC_HALF_IMM = bd(50);
    private static final BigDecimal DC_FULL_IMM = bd(60);
    private static final BigDecimal DC_EXT_IMM  = bd(80);

    // --- Daycare discounts (weekly prepay) ---
    // 1–3 days prepay
    private static final BigDecimal DC_HALF_PREPAY_1_3 = bd(45);
    private static final BigDecimal DC_FULL_PREPAY_1_3 = bd(50);
    private static final BigDecimal DC_EXT_PREPAY_1_3  = bd(70);
    // ≥4 days prepay
    private static final BigDecimal DC_HALF_PREPAY_4P = bd(40);
    private static final BigDecimal DC_FULL_PREPAY_4P = bd(45);
    private static final BigDecimal DC_EXT_PREPAY_4P  = bd(60);

    // --- Boarding immediate + tiered (per-night) ---
    private static final BigDecimal BRD_PERNIGHT_IMM = bd(90);

    // Prior-month tier: ≥4
    private static final BigDecimal BRD_PERNIGHT_T4  = bd(80);
    // Prior-month tier: ≥10
    private static final BigDecimal BRD_PERNIGHT_T10 = bd(75);
    // Prior-month tier: ≥16
    private static final BigDecimal BRD_PERNIGHT_T16 = bd(65);

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }

    private boolean isDaycare(Booking b) {
        String s = b.getServiceType();
        return s != null && s.toLowerCase().contains("daycare");
    }

    private boolean isBoarding(Booking b) {
        String s = b.getServiceType();
        return s != null && s.toLowerCase().contains("boarding");
    }

    private boolean isHalfDay(Booking b) {
        // Your strings: "Daycare (6 AM - 3 PM)" is the half-day
        String s = b.getServiceType();
        return s != null && s.contains("6 AM - 3 PM");
    }

    private boolean isExtended(Booking b) {
        // Extended is “till 9:30PM” in your spec; your label is "6 AM - 8 PM".
        // If you later add "Extended" variant string, adjust here.
        String s = b.getServiceType();
        return s != null && s.contains("8 PM"); // treating 6AM–8PM as the long/“full” block
    }

    private boolean isFullDay(Booking b) {
        // In your current 2 daycare SKUs you have 6–3 and 6–8.
        // We'll treat 6–8 as FULL for pricing selection (not Extended).
        // If you add a real Extended SKU later, update detection accordingly.
        return isDaycare(b) && isExtended(b); // map 6–8 to the “full-day” price band
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

    /**
     * Compute price for a single booking at evaluation time.
     * Uses locked weekly daycare prepay bundle + prior-month boarding tiers.
     * For daycare discount: booking must be 24h+ out, daycare, and customer opted-in (wantsAdvancePay).
     */
    public BigDecimal priceFor(Booking b) {
        if (b == null || b.getCustomer() == null) return BigDecimal.ZERO;

        // If we already locked a quote, prefer it (idempotent behavior)
        if (b.getQuotedRateAtLock() != null) return b.getQuotedRateAtLock();

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
            // For now, your two SKUs map: 6–3 => Half, 6–8 => Full
            return DC_FULL_IMM; // default to full for safety
        }

        // Count customer’s prepay daycare bookings that fall in the same Mon–Sun week
        User u = b.getCustomer();
        LocalDate ws = weekStart(b.getDate());
        LocalDate we = weekEnd(ws);

        List<Booking> weekDaycare = bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        u, "daycare", ws, we, "CANCELED");

        long prepayCount = weekDaycare.stream()
                .filter(x -> x.isWantsAdvancePay() && x.isAdvanceEligible())
                .count();

        boolean atLeast4 = prepayCount >= 4;

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
        // Prior-month boarding nights determine current per-night price
        User u = b.getCustomer();
        LocalDate pms = priorMonthStart(b.getDate());
        LocalDate pme = priorMonthEnd(b.getDate());

        List<Booking> prevMonthBoarding = bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        u, "boarding", pms, pme, "CANCELED");

        long nights = prevMonthBoarding.size();

        if (nights >= 16) return BRD_PERNIGHT_T16;
        if (nights >= 10) return BRD_PERNIGHT_T10;
        if (nights >= 4)  return BRD_PERNIGHT_T4;

        return BRD_PERNIGHT_IMM;
    }
}
