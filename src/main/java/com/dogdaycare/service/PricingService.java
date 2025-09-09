package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

@Service
public class PricingService {

    // Immediate rates (current behavior)
    public static final BigDecimal DC_HALF_IMM = bd(50);
    public static final BigDecimal DC_FULL_IMM = bd(60);
    public static final BigDecimal DC_EXT_IMM  = bd(80);

    public static final BigDecimal BR_NIGHT_IMM = bd(90);
    public static final BigDecimal BR_LAST_IMM  = bd(45);

    // Daycare discounted (1–3) and (≥4)
    public static final BigDecimal DC_HALF_T1 = bd(45);
    public static final BigDecimal DC_FULL_T1 = bd(50);
    public static final BigDecimal DC_EXT_T1  = bd(70);

    public static final BigDecimal DC_HALF_T4 = bd(40);
    public static final BigDecimal DC_FULL_T4 = bd(45);
    public static final BigDecimal DC_EXT_T4  = bd(60);

    // Boarding tiers (prev month)
    public static final BigDecimal BR_NIGHT_T4  = bd(80);    public static final BigDecimal BR_LAST_T4  = bd(40);
    public static final BigDecimal BR_NIGHT_T10 = bd(75);    public static final BigDecimal BR_LAST_T10 = bd(37.5);
    public static final BigDecimal BR_NIGHT_T16 = bd(65);    public static final BigDecimal BR_LAST_T16 = bd(32.5);

    public enum DaycareBucket { HALF, FULL, EXT, UNKNOWN }

    public boolean isDaycare(String serviceType) {
        return serviceType != null && serviceType.toLowerCase().contains("daycare");
    }

    public boolean isBoarding(String serviceType) {
        return serviceType != null && serviceType.toLowerCase().contains("boarding");
    }

    public DaycareBucket bucketForService(String serviceType) {
        if (serviceType == null) return DaycareBucket.UNKNOWN;
        String s = serviceType.toLowerCase();
        if (s.contains("6 am - 3 pm")) return DaycareBucket.HALF;
        if (s.contains("6 am - 8 pm")) return DaycareBucket.FULL;
        if (s.contains("extended") || s.contains("9:30")) return DaycareBucket.EXT;
        return DaycareBucket.UNKNOWN;
    }

    // === Existing immediate price (keeps AdminInvoiceController working as-is) ===
    public BigDecimal priceFor(String serviceType) {
        if (isDaycare(serviceType)) {
            switch (bucketForService(serviceType)) {
                case HALF: return DC_HALF_IMM;
                case FULL: return DC_FULL_IMM;
                case EXT:  return DC_EXT_IMM;
                default:   return DC_FULL_IMM;
            }
        } else if (isBoarding(serviceType)) {
            // Default per-night immediate (last-day logic can be added if you store it)
            return BR_NIGHT_IMM;
        }
        return DC_FULL_IMM;
    }

    // === Discount helpers we’ll use as we finish the weekly bundle ===
    public BigDecimal daycareImmediate(DaycareBucket b) {
        return switch (b) {
            case HALF -> DC_HALF_IMM;
            case FULL -> DC_FULL_IMM;
            case EXT  -> DC_EXT_IMM;
            default   -> DC_FULL_IMM;
        };
    }

    public BigDecimal daycareDiscounted(int prepaidCountInWeek, DaycareBucket b) {
        boolean tier4 = prepaidCountInWeek >= 4;
        return switch (b) {
            case HALF -> tier4 ? DC_HALF_T4 : DC_HALF_T1;
            case FULL -> tier4 ? DC_FULL_T4 : DC_FULL_T1;
            case EXT  -> tier4 ? DC_EXT_T4  : DC_EXT_T1;
            default   -> DC_FULL_T1;
        };
    }

    public static class BoardingTier {
        public final BigDecimal perNight;
        public final BigDecimal lastDay;
        public BoardingTier(BigDecimal night, BigDecimal last) { this.perNight = night; this.lastDay = last; }
    }

    public BoardingTier boardingTierForPrevMonth(int staysPrevMonth) {
        if (staysPrevMonth >= 16) return new BoardingTier(BR_NIGHT_T16, BR_LAST_T16);
        if (staysPrevMonth >= 10) return new BoardingTier(BR_NIGHT_T10, BR_LAST_T10);
        if (staysPrevMonth >= 4)  return new BoardingTier(BR_NIGHT_T4,  BR_LAST_T4);
        return new BoardingTier(BR_NIGHT_IMM, BR_LAST_IMM);
    }

    public static LocalDate weekStartMonday(LocalDate any) {
        return any.minusDays((any.getDayOfWeek().getValue() + 6) % 7); // Monday as start
    }

    public static LocalDate weekEndSunday(LocalDate weekStart) {
        return weekStart.plusDays(6);
    }

    public static LocalDate[] previousMonthRange(LocalDate anchorWeekStart) {
        LocalDate prev = anchorWeekStart.minusMonths(1);
        LocalDate start = prev.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end   = prev.with(TemporalAdjusters.lastDayOfMonth());
        return new LocalDate[]{start, end};
    }

    private static BigDecimal bd(double v){ return BigDecimal.valueOf(v); }
}
