package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
     * At payment time, compute the final eligible daycare set for the Mon–Sun week and
     * stamp all of them with in_prepay_bundle=true, quoted_rate_at_lock=<tier>, bundle_locked_at=now.
     * This overwrites earlier quotes for that week to the final tier (safe + idempotent).
     */
    @Transactional
    public void lockAndStampWeekForPayment(User customer, LocalDate anyDateInWeek) {
        Objects.requireNonNull(customer, "customer required");
        Objects.requireNonNull(anyDateInWeek, "anyDateInWeek required");

        LocalDate ws = pricingService.weekStartMonday(anyDateInWeek);
        LocalDate we = pricingService.weekEndSunday(anyDateInWeek);

        // Daycare bookings in week (not canceled)
        List<Booking> daycare = bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        customer, "daycare", ws, we, "CANCELED");

        // Eligible at payment time = wantsAdvancePay && advanceEligible
        List<Booking> eligible = daycare.stream()
                .filter(b -> b.isWantsAdvancePay() && b.isAdvanceEligible())
                .toList();

        if (eligible.isEmpty()) return;

        boolean atLeast4 = eligible.size() >= 4;
        OffsetDateTime now = OffsetDateTime.now();

        for (Booking b : eligible) {
            b.setInPrepayBundle(true);
            b.setQuotedRateAtLock(pricingService.quoteDaycareAtTier(b, atLeast4));
            b.setBundleLockedAt(now);
        }
        bookingRepository.saveAll(eligible);
    }
}
