package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PricingRulesTest {

    private Booking booking(
            String service, int dogs,
            boolean prepay, boolean eligible) {

        Booking b = new Booking();
        b.setServiceType(service);
        b.setDogCount(dogs);
        b.setWantsAdvancePay(prepay);
        b.setAdvanceEligible(eligible);
        b.setStatus("APPROVED");
        return b;
    }

    @Test
    void twoDogsOnTwoPrepaidDaysQualifyForFourPlus() {
        Booking monday = booking(
                PricingRules.DAYCARE_SHORT_SERVICE,
                2, true, true);

        Booking wednesday = booking(
                PricingRules.DAYCARE_LONG_SERVICE,
                2, true, true);

        int count = PricingRules.weeklyPrepayDogDays(
                List.of(monday, wednesday));

        assertEquals(4, count);
        assertEquals(new BigDecimal("40.00"),
                PricingRules.daycareRatePerDog(
                        PricingRules.DAYCARE_SHORT_SERVICE,
                        true, count));
    }

    @Test
    void nonPrepaidAndAfterHoursDoNotCountWeekly() {
        Booking prepaid = booking(
                PricingRules.DAYCARE_SHORT_SERVICE,
                1, true, true);

        Booking regular = booking(
                PricingRules.DAYCARE_LONG_SERVICE,
                2, false, true);

        Booking afterHours = booking(
                PricingRules.AFTER_HOURS_SERVICE,
                3, false, false);

        assertEquals(1,
                PricingRules.weeklyPrepayDogDays(
                        List.of(prepaid, regular, afterHours)));
    }

    @Test
    void canceledBookingsDoNotCount() {
        Booking b = booking(
                PricingRules.DAYCARE_SHORT_SERVICE,
                3, true, true);

        b.setStatus("CANCELED");

        assertEquals(0,
                PricingRules.weeklyPrepayDogDays(List.of(b)));
    }

    @Test
    void afterHoursAlwaysUsesFlatRate() {
        assertEquals(new BigDecimal("75.00"),
                PricingRules.daycareRatePerDog(
                        PricingRules.AFTER_HOURS_SERVICE,
                        false, 12));
    }

    @Test
    void boardingIncludesAllServiceTypes() {
        List<Booking> bookings = List.of(
                booking(PricingRules.DAYCARE_SHORT_SERVICE,
                        2, false, false),
                booking(PricingRules.AFTER_HOURS_SERVICE,
                        2, false, false),
                booking(PricingRules.BOARDING_SERVICE,
                        1, false, false)
        );

        assertEquals(5,
                PricingRules.boardingQualifyingDogDays(bookings));

        assertEquals(new BigDecimal("70.00"),
                PricingRules.boardingRatePerDog(5));

        assertEquals(new BigDecimal("35.00"),
                PricingRules.pickupFeePerDog(5));
    }

    @Test
    void boardingTopTierStartsAtTwelveDogDays() {
        assertEquals(new BigDecimal("75.00"),
                PricingRules.boardingRatePerDog(3));

        assertEquals(new BigDecimal("70.00"),
                PricingRules.boardingRatePerDog(11));

        assertEquals(new BigDecimal("60.00"),
                PricingRules.boardingRatePerDog(12));
    }

    @Test
    void prepayCutoffIsTwoAmOnServiceDate() {
        ZoneId zone = ZoneId.of("America/Los_Angeles");
        LocalDate serviceDate = LocalDate.of(2026, 11, 12);

        Clock beforeCutoff = Clock.fixed(
                Instant.parse("2026-11-12T09:59:59Z"),
                zone);

        Clock atCutoff = Clock.fixed(
                Instant.parse("2026-11-12T10:00:00Z"),
                zone);

        assertTrue(PricingRules.isAdvanceEligible(
                serviceDate, beforeCutoff));

        assertFalse(PricingRules.isAdvanceEligible(
                serviceDate, atCutoff));
    }

    @Test
    void onlyStandardDaycareIsAllowedOnPickupDay() {
        assertTrue(PricingRules.canBookOnPickupDay(
                PricingRules.DAYCARE_SHORT_SERVICE));

        assertTrue(PricingRules.canBookOnPickupDay(
                PricingRules.DAYCARE_LONG_SERVICE));

        assertFalse(PricingRules.canBookOnPickupDay(
                PricingRules.AFTER_HOURS_SERVICE));
    }

    @Test
    void onlyStandardDaycareWaivesPickupFee() {
        assertTrue(PricingRules.waivesPickupFee(
                PricingRules.DAYCARE_SHORT_SERVICE));

        assertTrue(PricingRules.waivesPickupFee(
                PricingRules.DAYCARE_LONG_SERVICE));

        assertFalse(PricingRules.waivesPickupFee(
                PricingRules.AFTER_HOURS_SERVICE));

        assertFalse(PricingRules.waivesPickupFee(
                PricingRules.BOARDING_SERVICE));
    }
}