package com.dogdaycare.service;

import com.dogdaycare.dto.SetForgetRuleRequest;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.SetForgetPlan;
import com.dogdaycare.model.SetForgetRule;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.SetForgetPlanRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SetForgetService {

    private final SetForgetPlanRepository setForgetPlanRepository;
    private final BookingRepository bookingRepository;
    private final BookingLimitService bookingLimitService;
    private final PricingService pricingService;
    private final BundleService bundleService;
    private final Clock clock;

    public SetForgetService(SetForgetPlanRepository setForgetPlanRepository,
                            BookingRepository bookingRepository,
                            BookingLimitService bookingLimitService,
                            PricingService pricingService,
                            BundleService bundleService,
                            Clock clock) {
        this.setForgetPlanRepository = setForgetPlanRepository;
        this.bookingRepository = bookingRepository;
        this.bookingLimitService = bookingLimitService;
        this.pricingService = pricingService;
        this.bundleService = bundleService;
        this.clock = clock;
    }

    public SetForgetPlan getActivePlan(User customer) {
        return setForgetPlanRepository.findByCustomerAndActiveTrue(customer).orElse(null);
    }

    @Transactional
    public SetForgetPlan saveOrUpdatePlan(User customer,
                                          boolean wantsAdvancePay,
                                          Integer dogCount,
                                          String durationOption,
                                          List<SetForgetRuleRequest> requestedRules) {

        if (dogCount == null) dogCount = 1;
        dogCount = Math.max(1, Math.min(5, dogCount));

        if (requestedRules == null || requestedRules.isEmpty()) {
            throw new IllegalArgumentException("At least one Set & Forget rule is required.");
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate endDate = calculateEndDate(today, durationOption);

        SetForgetPlan plan = setForgetPlanRepository
                .findByCustomerAndActiveTrue(customer)
                .orElseGet(SetForgetPlan::new);

        boolean isNew = (plan.getId() == null);
        if (isNew) {
            plan.setCustomer(customer);
            plan.setCreatedAt(LocalDateTime.now(clock));
        }

        plan.setActive(true);
        plan.setStartDate(today);
        plan.setEndDate(endDate);
        plan.setWantsAdvancePay(wantsAdvancePay);
        plan.setDogCount(dogCount);
        plan.setUpdatedAt(LocalDateTime.now(clock));

        plan.getRules().clear();

        requestedRules.stream()
                .sorted(Comparator.comparingInt(SetForgetRuleRequest::getDayOfWeek))
                .forEach(req -> {
                    validateRule(req);

                    SetForgetRule rule = new SetForgetRule();
                    rule.setPlan(plan);
                    rule.setDayOfWeek(req.getDayOfWeek());
                    rule.setServiceType(req.getServiceType().trim());
                    rule.setDropoffTime(LocalTime.parse(req.getDropoffTime().trim()));
                    rule.setActive(true);

                    plan.getRules().add(rule);
                });

        SetForgetPlan savedPlan = setForgetPlanRepository.save(plan);

        clearFutureGeneratedBookings(savedPlan);
        generateBookingsForPlan(savedPlan);

        return savedPlan;
    }

    @Transactional
    public int generateBookingsForActivePlan(User customer) {
        SetForgetPlan plan = setForgetPlanRepository.findByCustomerAndActiveTrue(customer).orElse(null);
        if (plan == null || !plan.isActive()) {
            return 0;
        }
        return generateBookingsForPlan(plan);
    }

    @Transactional
    public int clearFutureGeneratedBookings(User customer) {
        SetForgetPlan plan = setForgetPlanRepository.findByCustomerAndActiveTrue(customer).orElse(null);
        if (plan == null || !plan.isActive()) {
            return 0;
        }
        return clearFutureGeneratedBookings(plan);
    }

    @Transactional
    public int cancelPlan(User customer) {
        SetForgetPlan plan = setForgetPlanRepository.findByCustomerAndActiveTrue(customer).orElse(null);
        if (plan == null || !plan.isActive()) {
            return 0;
        }

        int deleted = clearFutureGeneratedBookingsWith24HourProtection(plan);

        plan.setActive(false);
        plan.setUpdatedAt(LocalDateTime.now(clock));
        setForgetPlanRepository.save(plan);

        return deleted;
    }

    private int clearFutureGeneratedBookings(SetForgetPlan plan) {
        LocalDate today = LocalDate.now(clock);

        List<Booking> existing = bookingRepository.findBySetForgetPlanAndDateGreaterThanEqual(plan, today);
        int count = existing.size();

        if (count > 0) {
            bookingRepository.deleteBySetForgetPlanAndDateGreaterThanEqual(plan, today);
        }

        return count;
    }

    private int clearFutureGeneratedBookingsWith24HourProtection(SetForgetPlan plan) {
        LocalDate today = LocalDate.now(clock);

        List<Booking> existing = bookingRepository
                .findBySetForgetPlanAndDateGreaterThanEqualOrderByDateAsc(plan, today);

        List<Booking> toDelete = existing.stream()
                .filter(this::isAtLeast24HoursAway)
                .toList();

        if (!toDelete.isEmpty()) {
            bookingRepository.deleteAll(toDelete);
        }

        return toDelete.size();
    }

    private int generateBookingsForPlan(SetForgetPlan plan) {
        if (plan == null || !plan.isActive()) {
            return 0;
        }

        User customer = plan.getCustomer();
        LocalDate today = LocalDate.now(clock);
        LocalDate horizonEnd = today.plusDays(60);

        LocalDate effectiveEnd = plan.getEndDate().isBefore(horizonEnd)
                ? plan.getEndDate()
                : horizonEnd;

        if (effectiveEnd.isBefore(today)) {
            return 0;
        }

        Map<LocalDate, List<Booking>> candidatesByWeek = new LinkedHashMap<>();

        for (SetForgetRule rule : plan.getRules()) {
            if (!rule.isActive()) {
                continue;
            }

            LocalDate firstDate = nextOccurrence(today, rule.getDayOfWeek());

            for (LocalDate d = firstDate; !d.isAfter(effectiveEnd); d = d.plusWeeks(1)) {

                boolean alreadyBooked = !bookingRepository
                        .findByCustomerAndDateAndStatusNotIgnoreCase(customer, d, "CANCELED")
                        .isEmpty();

                if (alreadyBooked) {
                    continue;
                }

                boolean canBook = bookingLimitService.canCustomerBook(d, rule.getServiceType());
                if (!canBook) {
                    continue;
                }

                Booking booking = new Booking();
                booking.setCustomer(customer);
                booking.setServiceType(rule.getServiceType());
                booking.setDate(d);
                booking.setTime(rule.getDropoffTime());
                booking.setStatus("APPROVED");
                booking.setCreatedAt(LocalDateTime.now(clock));
                booking.setDogCount(plan.getDogCount());
                booking.setSetForgetPlan(plan);

                LocalDate weekStart = pricingService.weekStartMonday(d);
                candidatesByWeek.computeIfAbsent(weekStart, k -> new ArrayList<>()).add(booking);
            }
        }

        List<Booking> toCreate = new ArrayList<>();

        for (Map.Entry<LocalDate, List<Booking>> entry : candidatesByWeek.entrySet()) {
            LocalDate weekStart = entry.getKey();
            LocalDate weekEnd = pricingService.weekEndSunday(weekStart);
            List<Booking> weekCandidates = entry.getValue();

            boolean weekAlreadyPaid = bundleService.hasWeekPaid(customer, weekStart);

            long existingEligible = bookingRepository
                    .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                            customer, "daycare", weekStart, weekEnd, "CANCELED"
                    ).stream()
                    .filter(b -> b.isWantsAdvancePay() && b.isAdvanceEligible())
                    .count();

            long selectedEligibleInWeek = weekCandidates.stream()
                    .filter(this::isDaycare)
                    .filter(b -> !isAfterHours(b))
                    .filter(b -> isAdvanceEligible(b.getDate(), b.getTime()))
                    .filter(b -> plan.isWantsAdvancePay() && !weekAlreadyPaid)
                    .count();

            boolean atLeast4ForWeek = (existingEligible + selectedEligibleInWeek) >= 4;

            for (Booking booking : weekCandidates) {
                boolean isDaycare = isDaycare(booking);
                boolean isAfterHours = isAfterHours(booking);

                boolean advanceEligible = false;
                boolean wantsAdvancePayFinal = false;

                if (isDaycare && !isAfterHours) {
                    advanceEligible = isAdvanceEligible(booking.getDate(), booking.getTime());
                    wantsAdvancePayFinal = advanceEligible && plan.isWantsAdvancePay() && !weekAlreadyPaid;
                }

                booking.setAdvanceEligible(advanceEligible);
                booking.setWantsAdvancePay(wantsAdvancePayFinal);
                booking.setInPrepayBundle(false);
                booking.setPaid(false);
                booking.setPaidAt(null);
                booking.setBundleLockedAt(null);

                BigDecimal perDogBase;

                if (isAfterHours) {
                    perDogBase = pricingService.previewDaycarePrice(
                            customer,
                            booking.getDate(),
                            booking.getServiceType(),
                            false,
                            false
                    );
                } else if (isDaycare) {
                    if (wantsAdvancePayFinal) {
                        perDogBase = pricingService.quoteDaycareAtTier(booking, atLeast4ForWeek);
                    } else {
                        perDogBase = pricingService.previewDaycarePrice(
                                customer,
                                booking.getDate(),
                                booking.getServiceType(),
                                advanceEligible,
                                false
                        );
                    }
                } else {
                    perDogBase = BigDecimal.ZERO;
                }

                BigDecimal total = perDogBase
                        .multiply(BigDecimal.valueOf(booking.getDogCount()))
                        .setScale(2, RoundingMode.HALF_UP);

                booking.setQuotedRateAtLock(total);
                toCreate.add(booking);
            }
        }

        if (!toCreate.isEmpty()) {
            bookingRepository.saveAll(toCreate);
        }

        return toCreate.size();
    }

    private LocalDate calculateEndDate(LocalDate startDate, String durationOption) {
        if (durationOption == null || durationOption.isBlank()) {
            throw new IllegalArgumentException("Duration option is required.");
        }

        return switch (durationOption.trim().toUpperCase()) {
            case "THREE_MONTHS" -> startDate.plusMonths(3);
            case "SIX_MONTHS" -> startDate.plusMonths(6);
            case "TWELVE_MONTHS" -> startDate.plusMonths(12);
            case "INDEFINITE" -> startDate.plusYears(1);
            default -> throw new IllegalArgumentException("Invalid duration option: " + durationOption);
        };
    }

    private void validateRule(SetForgetRuleRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Rule cannot be null.");
        }

        if (req.getDayOfWeek() < 1 || req.getDayOfWeek() > 7) {
            throw new IllegalArgumentException("Day of week must be between 1 and 7.");
        }

        if (req.getServiceType() == null || req.getServiceType().isBlank()) {
            throw new IllegalArgumentException("Service type is required.");
        }

        String service = req.getServiceType().trim();
        boolean allowed =
                "Daycare (6 AM - 3 PM)".equalsIgnoreCase(service) ||
                        "Daycare (6 AM - 8 PM)".equalsIgnoreCase(service) ||
                        "Daycare After Hours (6 AM - 11 PM)".equalsIgnoreCase(service);

        if (!allowed) {
            throw new IllegalArgumentException("Only daycare services are allowed for Set & Forget.");
        }

        if (req.getDropoffTime() == null || req.getDropoffTime().isBlank()) {
            throw new IllegalArgumentException("Dropoff time is required.");
        }

        LocalTime.parse(req.getDropoffTime().trim());
    }

    private LocalDate nextOccurrence(LocalDate fromDate, short dayOfWeekValue) {
        DayOfWeek target = DayOfWeek.of(dayOfWeekValue);

        LocalDate d = fromDate.plusDays(1);
        while (d.getDayOfWeek() != target) {
            d = d.plusDays(1);
        }
        return d;
    }

    private boolean isAdvanceEligible(LocalDate date, LocalTime time) {
        if (date == null || time == null) {
            return false;
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime bookingZdt = ZonedDateTime.of(date, time, clock.getZone());
        long hours = Duration.between(now, bookingZdt).toHours();
        return hours >= 24;
    }

    private boolean isAtLeast24HoursAway(Booking booking) {
        if (booking == null || booking.getDate() == null || booking.getTime() == null) {
            return false;
        }

        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime bookingZdt = ZonedDateTime.of(booking.getDate(), booking.getTime(), clock.getZone());
        long hours = Duration.between(now, bookingZdt).toHours();
        return hours >= 24;
    }

    private boolean isDaycare(Booking booking) {
        String s = booking.getServiceType();
        return s != null && s.toLowerCase().contains("daycare");
    }

    private boolean isAfterHours(Booking booking) {
        String s = booking.getServiceType();
        if (s == null) {
            return false;
        }
        String sl = s.toLowerCase();
        return sl.contains("after hours") || s.contains("6 AM - 11 PM");
    }
}