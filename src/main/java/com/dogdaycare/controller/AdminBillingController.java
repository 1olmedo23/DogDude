package com.dogdaycare.controller;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.model.WeeklyBillingStatus;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.repository.WeeklyBillingStatusRepository;
import com.dogdaycare.service.PricingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBillingController {

    private final UserRepository userRepo;
    private final BookingRepository bookingRepo;
    private final WeeklyBillingStatusRepository weeklyRepo;
    private final PricingService pricing;

    public AdminBillingController(UserRepository userRepo,
                                  BookingRepository bookingRepo,
                                  WeeklyBillingStatusRepository weeklyRepo,
                                  PricingService pricing) {
        this.userRepo = userRepo;
        this.bookingRepo = bookingRepo;
        this.weeklyRepo = weeklyRepo;
        this.pricing = pricing;
    }

    // === 5.1 Lock the weekly prepay bundle ===
    @PostMapping("/prepay/lock")
    public Map<String, Object> lockPrepayBundle(@RequestParam("email") String email,
                                                @RequestParam("weekStart") String weekStartIso) {
        User user = userRepo.findByUsername(email).orElseThrow();
        LocalDate weekStart = LocalDate.parse(weekStartIso);
        LocalDate weekEnd   = PricingService.weekEndSunday(weekStart);

        // Get all advance-eligible daycare bookings for the week where customer opted-in
        List<Booking> weekBookings = bookingRepo.findByCustomerAndDateBetween(user, weekStart, weekEnd);
        List<Booking> daycareOpted = weekBookings.stream()
                .filter(b -> pricing.isDaycare(b.getServiceType()))
                .filter(b -> Boolean.TRUE.equals(b.isAdvanceEligible()))
                .filter(b -> Boolean.TRUE.equals(b.isWantsAdvancePay()))
                .collect(Collectors.toList());

        // Count to determine tier
        int prepaidCount = daycareOpted.size();

        // Create/find weekly status
        WeeklyBillingStatus wbs = weeklyRepo.findByUserAndWeekStart(user, weekStart)
                .orElseGet(() -> new WeeklyBillingStatus(user, weekStart));
        wbs.setPrepayLockedAt(LocalDateTime.now());
        weeklyRepo.save(wbs);

        // Mark bookings as inPrepayBundle and set quotedRateAtLock
        for (Booking b : daycareOpted) {
            b.setInPrepayBundle(true);
            var bucket = pricing.bucketForService(b.getServiceType());
            BigDecimal rate = pricing.daycareDiscounted(prepaidCount, bucket);
            b.setQuotedRateAtLock(rate);
        }
        bookingRepo.saveAll(daycareOpted);

        return Map.of(
                "email", email,
                "weekStart", weekStartIso,
                "prepaidCount", prepaidCount,
                "locked", true
        );
    }

    // === 5.2 Mark a week paid (single source of truth for both tabs) ===
    @PostMapping("/billing/mark-paid")
    public Map<String, Object> markWeekPaid(@RequestParam("email") String email,
                                            @RequestParam("weekStart") String weekStartIso) {
        User user = userRepo.findByUsername(email).orElseThrow();
        LocalDate weekStart = LocalDate.parse(weekStartIso);

        WeeklyBillingStatus wbs = weeklyRepo.findByUserAndWeekStart(user, weekStart)
                .orElseGet(() -> new WeeklyBillingStatus(user, weekStart));
        wbs.setPaid(true);
        weeklyRepo.save(wbs);

        return Map.of("email", email, "weekStart", weekStartIso, "paid", true);
    }

    // === 5.3 Weekly invoice rows (JSON used by your custom.js) ===

    // === helper: previous-month boarding nights ===
    private int countPrevMonthBoardingNights(User user, LocalDate weekStart) {
        LocalDate[] prev = PricingService.previousMonthRange(weekStart);
        // Count number of boarding bookings in previous month (1 booking == 1 night in your current model)
        return bookingRepo.findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetween(
                user, "boarding", prev[0], prev[1]).size();
    }
}
