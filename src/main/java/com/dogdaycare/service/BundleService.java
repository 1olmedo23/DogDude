package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class BundleService {

    private final BookingRepository bookingRepository;
    private final PricingService pricingService;

    public BundleService(BookingRepository bookingRepository, PricingService pricingService) {
        this.bookingRepository = bookingRepository;
        this.pricingService = pricingService;
    }

    public boolean hasWeekPaid(User customer, LocalDate anyDateInWeek) {
        if (customer == null || anyDateInWeek == null) return false;
        LocalDate ws = pricingService.weekStartMonday(anyDateInWeek);
        LocalDate we = pricingService.weekEndSunday(anyDateInWeek);
        return bookingRepository
                .findByCustomerAndDateBetweenAndStatusNotIgnoreCase(customer, ws, we, "CANCELED")
                .stream()
                .anyMatch(Booking::isPaid);
    }

    /**
     * Finalize eligible weekly prepaid daycare prices
     * BEFORE marking bookings paid.
     *
     * quotedRateAtLock stores the TOTAL for all dogs.
     * Previously paid bookings are never modified.
     */
    @Transactional
    public void lockAndStampWeekForPayment(
            User customer,
            LocalDate anyDateInWeek) {

        Objects.requireNonNull(customer, "customer required");
        Objects.requireNonNull(
                anyDateInWeek, "anyDateInWeek required"
        );

        LocalDate ws =
                pricingService.weekStartMonday(anyDateInWeek);

        LocalDate we =
                pricingService.weekEndSunday(anyDateInWeek);

        List<Booking> daycare = bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        customer,
                        "daycare",
                        ws,
                        we,
                        "CANCELED"
                );

        // All qualifying prepaid dog-days determine
        // the weekly discount threshold.
        int qualifyingDogDays =
                PricingRules.weeklyPrepayDogDays(daycare);

        boolean atLeast4 = qualifyingDogDays >= 4;

        // Only unpaid, eligible standard daycare bookings
        // can receive a new finalized price.
        List<Booking> toLock = daycare.stream()
                .filter(b -> !b.isPaid())
                .filter(b -> PricingRules.isStandardDaycare(
                        b.getServiceType()))
                .filter(Booking::isWantsAdvancePay)
                .filter(Booking::isAdvanceEligible)
                .toList();

        if (toLock.isEmpty()) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();

        for (Booking booking : toLock) {

            BigDecimal perDog =
                    pricingService.quoteDaycareAtTier(
                            booking,
                            atLeast4
                    );

            int dogs = booking.getDogCount() == null
                    ? 1
                    : Math.max(1, booking.getDogCount());

            BigDecimal total = perDog
                    .multiply(BigDecimal.valueOf(dogs))
                    .setScale(2, RoundingMode.HALF_UP);

            booking.setQuotedRateAtLock(total);
            booking.setInPrepayBundle(true);
            booking.setBundleLockedAt(now);
        }

        bookingRepository.saveAll(toLock);
    }
}
