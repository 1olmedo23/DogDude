package com.dogdaycare.service;

import com.dogdaycare.dto.SetForgetRuleRequest;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.SetForgetException;
import com.dogdaycare.model.SetForgetPlan;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.SetForgetExceptionRepository;
import com.dogdaycare.repository.SetForgetPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SetForgetServiceTest {

    private SetForgetPlanRepository setForgetPlanRepository;
    private BookingRepository bookingRepository;
    private SetForgetExceptionRepository setForgetExceptionRepository;
    private BookingLimitService bookingLimitService;
    private PricingService pricingService;
    private BundleService bundleService;

    private SetForgetService setForgetService;

    private User customer;

    private final ZoneId zone =
            ZoneId.of("America/Los_Angeles");

    /*
     * Monday, September 14, 2026 at 10:00 AM Pacific.
     */
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-14T17:00:00Z"),
            zone
    );

    @Test
    void generateBookings_exactly24HoursAway_isAllowed() {
        SetForgetPlan plan = activeTuesdayPlan();

        LocalDate tomorrow = LocalDate.of(2026, 9, 15);

        // Fixed clock: Monday at 10:00 AM.
        // Tuesday at 10:00 AM is exactly 24 hours away.
        plan.getRules().get(0).setDropoffTime(LocalTime.of(10, 0));
        plan.setEndDate(tomorrow);

        when(setForgetPlanRepository.findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.of(plan));

        when(setForgetExceptionRepository.existsByPlanAndExceptionDate(
                plan, tomorrow
        )).thenReturn(false);

        when(bookingRepository.findByCustomerAndDateAndStatusNotIgnoreCase(
                customer, tomorrow, "CANCELED"
        )).thenReturn(List.of());

        when(bookingLimitService.canCustomerBook(
                tomorrow, "Daycare (6 AM - 8 PM)"
        )).thenReturn(true);

        LocalDate monday = LocalDate.of(2026, 9, 14);
        LocalDate sunday = LocalDate.of(2026, 9, 20);

        when(pricingService.weekStartMonday(tomorrow))
                .thenReturn(monday);

        when(pricingService.weekEndSunday(monday))
                .thenReturn(sunday);

        when(bundleService.hasWeekPaid(customer, monday))
                .thenReturn(false);

        when(bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        customer, "daycare", monday, sunday, "CANCELED"
                ))
                .thenReturn(List.of());

        when(pricingService.previewDaycarePrice(
                customer,
                tomorrow,
                "Daycare (6 AM - 8 PM)",
                true,
                false
        )).thenReturn(new BigDecimal("50.00"));

        int generated = setForgetService.generateBookingsForActivePlan(customer);

        assertEquals(1, generated);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Booking>> captor =
                ArgumentCaptor.forClass(List.class);

        verify(bookingRepository).saveAll(captor.capture());

        List<Booking> saved = captor.getValue();

        assertEquals(1, saved.size());
        assertEquals(tomorrow, saved.get(0).getDate());
        assertEquals(LocalTime.of(10, 0), saved.get(0).getTime());
        assertSame(plan, saved.get(0).getSetForgetPlan());
    }

    @Test
    void generateBookings_existingManualBooking_isPreservedAndSkipped() {
        SetForgetPlan plan = activeTuesdayPlan();

        // This Tuesday is safely outside the 24-hour cutoff.
        LocalDate bookedDate = LocalDate.of(2026, 9, 22);
        plan.setEndDate(bookedDate);

        Booking manualBooking = new Booking();
        manualBooking.setId(900L);
        manualBooking.setCustomer(customer);
        manualBooking.setDate(bookedDate);
        manualBooking.setTime(LocalTime.of(8, 0));
        manualBooking.setServiceType("Daycare (6 AM - 3 PM)");
        manualBooking.setStatus("APPROVED");
        manualBooking.setDogCount(2);
        manualBooking.setPaid(true);
        manualBooking.setQuotedRateAtLock(new BigDecimal("100.00"));

        // No plan association: this is a manual reservation.
        assertNull(manualBooking.getSetForgetPlan());

        when(setForgetPlanRepository.findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.of(plan));

        when(setForgetExceptionRepository.existsByPlanAndExceptionDate(
                plan, bookedDate
        )).thenReturn(false);

        when(bookingRepository.findByCustomerAndDateAndStatusNotIgnoreCase(
                customer, bookedDate, "CANCELED"
        )).thenReturn(List.of(manualBooking));

        int generated =
                setForgetService.generateBookingsForActivePlan(customer);

        assertEquals(0, generated);

        // Confirm that the existing-booking check was actually reached.
        verify(bookingRepository)
                .findByCustomerAndDateAndStatusNotIgnoreCase(
                        customer, bookedDate, "CANCELED"
                );

        verify(bookingRepository, never()).save(any(Booking.class));
        verify(bookingRepository, never()).saveAll(anyList());

        // A conflicting date must be skipped before capacity or pricing.
        verifyNoInteractions(bookingLimitService, pricingService, bundleService);

        assertTrue(manualBooking.isPaid());
        assertNull(manualBooking.getSetForgetPlan());
        assertEquals(LocalTime.of(8, 0), manualBooking.getTime());
        assertEquals("Daycare (6 AM - 3 PM)", manualBooking.getServiceType());
        assertEquals(
                0,
                new BigDecimal("100.00")
                        .compareTo(manualBooking.getQuotedRateAtLock())
        );
    }

    @Test
    void cancelPlan_preservesPaidBooking_andDeletesUnpaidFutureBooking() {
        SetForgetPlan plan = activeTuesdayPlan();

        Booking paidBooking = generatedBooking(
                plan,
                LocalDate.of(2026, 9, 22),
                LocalTime.of(6, 30)
        );
        paidBooking.setId(910L);
        paidBooking.setPaid(true);

        java.time.LocalDateTime paidAt =
                java.time.LocalDateTime.of(2026, 9, 14, 9, 0);

        paidBooking.setPaidAt(paidAt);
        paidBooking.setQuotedRateAtLock(new BigDecimal("100.00"));
        paidBooking.setManualAdjustmentAmount(new BigDecimal("-5.00"));
        paidBooking.setManualAdjustmentReason("Approved discount");

        Booking unpaidBooking = generatedBooking(
                plan,
                LocalDate.of(2026, 9, 29),
                LocalTime.of(6, 30)
        );
        unpaidBooking.setId(911L);
        unpaidBooking.setPaid(false);

        when(setForgetPlanRepository.findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.of(plan));

        when(bookingRepository
                .findBySetForgetPlanAndDateGreaterThanEqualOrderByDateAsc(
                        plan,
                        LocalDate.of(2026, 9, 14)
                ))
                .thenReturn(List.of(paidBooking, unpaidBooking));

        int deleted = setForgetService.cancelPlan(customer);

        assertEquals(1, deleted);
        assertFalse(plan.isActive());
        verify(setForgetPlanRepository).save(plan);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Booking>> captor =
                ArgumentCaptor.forClass(Iterable.class);

        verify(bookingRepository).deleteAll(captor.capture());

        List<Booking> removed = new java.util.ArrayList<>();
        captor.getValue().forEach(removed::add);

        assertEquals(List.of(unpaidBooking), removed);

        assertTrue(paidBooking.isPaid());
        assertEquals(paidAt, paidBooking.getPaidAt());
        assertEquals("APPROVED", paidBooking.getStatus());
        assertSame(plan, paidBooking.getSetForgetPlan());

        assertEquals(
                new BigDecimal("100.00"),
                paidBooking.getQuotedRateAtLock()
        );
        assertEquals(
                new BigDecimal("-5.00"),
                paidBooking.getManualAdjustmentAmount()
        );
        assertEquals(
                "Approved discount",
                paidBooking.getManualAdjustmentReason()
        );
    }

    @Test
    void editPlan_preservesProtectedAndMatchingBookings() {
        SetForgetPlan plan = activeTuesdayPlan();

        Booking paid = generatedBooking(
                plan,
                LocalDate.of(2026, 9, 22),
                LocalTime.of(6, 30)
        );
        paid.setPaid(true);
        paid.setQuotedRateAtLock(new BigDecimal("50.00"));

        Booking inside24Hours = generatedBooking(
                plan,
                LocalDate.of(2026, 9, 15),
                LocalTime.of(6, 30)
        );

        // Already matches the updated Tuesday 8:00 AM schedule.
        Booking matching = generatedBooking(
                plan,
                LocalDate.of(2026, 9, 29),
                LocalTime.of(8, 0)
        );
        matching.setQuotedRateAtLock(new BigDecimal("45.00"));

        // Unpaid, safely in the future, and has the old drop-off time.
        Booking needsReplacement = generatedBooking(
                plan,
                LocalDate.of(2026, 10, 6),
                LocalTime.of(6, 30)
        );

        Booking adjusted = generatedBooking(
                plan,
                LocalDate.of(2026, 10, 13),
                LocalTime.of(6, 30)
        );
        adjusted.setManualAdjustmentAmount(new BigDecimal("-5.00"));
        adjusted.setManualAdjustmentReason("Approved discount");

        Booking canceled = generatedBooking(
                plan,
                LocalDate.of(2026, 10, 20),
                LocalTime.of(6, 30)
        );
        canceled.setStatus("CANCELED");

        when(setForgetPlanRepository.findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.of(plan));

        when(setForgetPlanRepository.save(any(SetForgetPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(bookingRepository.findBySetForgetPlanAndDateGreaterThanEqual(
                plan,
                LocalDate.of(2026, 9, 14)
        )).thenReturn(List.of(
                paid,
                inside24Hours,
                matching,
                needsReplacement,
                adjusted,
                canceled
        ));

        // Isolate the edit cleanup: no new reservations can be generated.
        when(bookingLimitService.canCustomerBook(
                any(LocalDate.class),
                anyString()
        )).thenReturn(false);

        SetForgetPlan saved = setForgetService.saveOrUpdatePlan(
                customer,
                false,
                1,
                "THREE_MONTHS",
                List.of(rule(
                        (short) 2,
                        "Daycare (6 AM - 8 PM)",
                        "08:00"
                ))
        );

        assertSame(plan, saved);
        assertTrue(saved.isActive());
        assertEquals(1, saved.getRules().size());
        assertEquals(
                LocalTime.of(8, 0),
                saved.getRules().get(0).getDropoffTime()
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Booking>> captor =
                ArgumentCaptor.forClass(Iterable.class);

        verify(bookingRepository).deleteAll(captor.capture());

        List<Booking> removed = new java.util.ArrayList<>();
        captor.getValue().forEach(removed::add);

        assertEquals(List.of(needsReplacement), removed);
        verify(bookingRepository).flush();

        // The old broad deletion must no longer be used.
        verify(bookingRepository, never())
                .deleteBySetForgetPlanAndDateGreaterThanEqual(
                        any(SetForgetPlan.class),
                        any(LocalDate.class)
                );

        assertTrue(paid.isPaid());
        assertEquals(
                new BigDecimal("50.00"),
                paid.getQuotedRateAtLock()
        );
        assertEquals(
                new BigDecimal("45.00"),
                matching.getQuotedRateAtLock()
        );
        assertEquals(
                LocalTime.of(6, 30),
                inside24Hours.getTime()
        );
        assertEquals(
                new BigDecimal("-5.00"),
                adjusted.getManualAdjustmentAmount()
        );
        assertEquals("CANCELED", canceled.getStatus());
    }

    @Test
    void savePlan_distinguishesIndefiniteFromFixedTwelveMonths() {
        when(setForgetPlanRepository.save(any(SetForgetPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(setForgetPlanRepository.findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.empty());

        // Check the duration choice without generating bookings.
        when(bookingLimitService.canCustomerBook(
                any(LocalDate.class), anyString()
        )).thenReturn(false);

        List<SetForgetRuleRequest> rules = List.of(
                rule((short) 2, "Daycare (6 AM - 8 PM)", "06:30")
        );

        SetForgetPlan indefinite = setForgetService.saveOrUpdatePlan(
                customer, false, 1, "INDEFINITE", rules
        );

        assertTrue(indefinite.isAutoRenew());
        assertEquals(
                LocalDate.now(clock).plusYears(1),
                indefinite.getEndDate()
        );

        indefinite.setId(200L);
        when(setForgetPlanRepository.findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.of(indefinite));

        SetForgetPlan fixed = setForgetService.saveOrUpdatePlan(
                customer, false, 1, "TWELVE_MONTHS", rules
        );

        assertSame(indefinite, fixed);
        assertFalse(fixed.isAutoRenew());
        assertEquals(
                LocalDate.now(clock).plusMonths(12),
                fixed.getEndDate()
        );
    }

    @BeforeEach
    void setup() {

        setForgetPlanRepository =
                mock(SetForgetPlanRepository.class);

        bookingRepository =
                mock(BookingRepository.class);

        setForgetExceptionRepository =
                mock(SetForgetExceptionRepository.class);

        bookingLimitService =
                mock(BookingLimitService.class);

        pricingService =
                mock(PricingService.class);

        bundleService =
                mock(BundleService.class);

        setForgetService = new SetForgetService(
                setForgetPlanRepository,
                bookingRepository,
                setForgetExceptionRepository,
                bookingLimitService,
                pricingService,
                bundleService,
                clock
        );

        customer = new User();
        customer.setId(100L);
        customer.setUsername("customer@test.local");
    }

    @Test
    void savePlan_generatesRecurringBookings()
            throws Exception {

        SetForgetRuleRequest rule =
                rule(
                        (short) 2,
                        "Daycare (6 AM - 8 PM)",
                        "06:30"
                );

        /*
         * Current time: Monday 9/14/2026 at 10:00 AM Pacific.
         * Tuesday 9/15 at 6:30 AM is only 20.5 hours away.
         * Skip that occurrence; the first eligible Tuesday is 9/22.
         */
        LocalDate firstBookingDate =
                LocalDate.of(2026, 9, 22);

        when(setForgetPlanRepository
                .findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.empty());

        when(setForgetPlanRepository.save(
                any(SetForgetPlan.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(0)
        );

        when(bookingRepository
                .findBySetForgetPlanAndDateGreaterThanEqual(
                        any(SetForgetPlan.class),
                        any(LocalDate.class)
                ))
                .thenReturn(List.of());

        when(setForgetExceptionRepository
                .existsByPlanAndExceptionDate(
                        any(SetForgetPlan.class),
                        any(LocalDate.class)
                ))
                .thenReturn(false);

        when(bookingRepository
                .findByCustomerAndDateAndStatusNotIgnoreCase(
                        eq(customer),
                        any(LocalDate.class),
                        eq("CANCELED")
                ))
                .thenReturn(List.of());

        when(bookingLimitService.canCustomerBook(
                any(LocalDate.class),
                eq("Daycare (6 AM - 8 PM)")
        )).thenReturn(true);

        when(pricingService.weekStartMonday(
                any(LocalDate.class)
        )).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            return date.with(
                    java.time.DayOfWeek.MONDAY
            );
        });

        when(pricingService.weekEndSunday(
                any(LocalDate.class)
        )).thenAnswer(invocation -> {
            LocalDate monday =
                    invocation.getArgument(0);
            return monday.plusDays(6);
        });

        when(bundleService.hasWeekPaid(
                eq(customer),
                any(LocalDate.class)
        )).thenReturn(false);

        when(bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        eq(customer),
                        eq("daycare"),
                        any(LocalDate.class),
                        any(LocalDate.class),
                        eq("CANCELED")
                ))
                .thenReturn(List.of());

        when(pricingService.previewDaycarePrice(
                eq(customer),
                any(LocalDate.class),
                eq("Daycare (6 AM - 8 PM)"),
                anyBoolean(),
                eq(false)
        )).thenReturn(
                new BigDecimal("50.00")
        );

        setForgetService.saveOrUpdatePlan(
                customer,
                false,
                2,
                "THREE_MONTHS",
                List.of(rule)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Booking>> captor =
                ArgumentCaptor.forClass(List.class);

        verify(bookingRepository)
                .saveAll(captor.capture());

        List<Booking> generated =
                captor.getValue();

        assertFalse(generated.isEmpty());

        assertTrue(
                generated.stream().noneMatch(b ->
                        LocalDate.of(2026, 9, 15).equals(b.getDate())
                ),
                "Tuesday inside the 24-hour cutoff must not be generated."
        );

        assertTrue(
                generated.stream().allMatch(b ->
                        java.time.Duration.between(
                                clock.instant(),
                                b.getDate()
                                        .atTime(b.getTime())
                                        .atZone(zone)
                                        .toInstant()
                        ).compareTo(java.time.Duration.ofHours(24)) >= 0
                ),
                "Every generated booking must be at least 24 hours away."
        );

        Booking first = generated.stream()
                .filter(b ->
                        firstBookingDate.equals(b.getDate())
                )
                .findFirst()
                .orElseThrow();

        assertSame(
                customer,
                first.getCustomer()
        );

        assertEquals(
                "Daycare (6 AM - 8 PM)",
                first.getServiceType()
        );

        assertEquals(
                LocalTime.of(6, 30),
                first.getTime()
        );

        assertEquals(
                "APPROVED",
                first.getStatus()
        );

        assertEquals(
                2,
                first.getDogCount()
        );

        assertNotNull(
                first.getSetForgetPlan()
        );

        assertEquals(
                0,
                new BigDecimal("100.00")
                        .compareTo(
                                first.getQuotedRateAtLock()
                        )
        );
    }

    @Test
    void existingException_preventsDateFromBeingRegenerated()
            throws Exception {

        SetForgetPlan plan =
                activeTuesdayPlan();

        LocalDate exceptionDate =
                LocalDate.of(2026, 9, 22);

        when(setForgetPlanRepository
                .findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.of(plan));

        when(setForgetExceptionRepository
                .existsByPlanAndExceptionDate(
                        plan,
                        exceptionDate
                ))
                .thenReturn(true);

        /*
         * Make every later recurring Tuesday unavailable.
         * That isolates the test to the exception date.
         */
        when(bookingLimitService.canCustomerBook(
                any(LocalDate.class),
                anyString()
        )).thenReturn(false);

        int generated =
                setForgetService
                        .generateBookingsForActivePlan(
                                customer
                        );

        assertEquals(
                0,
                generated
        );

        verify(bookingRepository, never())
                .saveAll(anyList());

        verify(bookingLimitService, never())
                .canCustomerBook(
                        eq(exceptionDate),
                        anyString()
                );
    }

    @Test
    void cancelPlan_keepsBookingInside24Hours_andDeletesLaterBooking() {

        SetForgetPlan plan =
                activeTuesdayPlan();

        /*
         * Current time is:
         * Monday 9/14/2026 10:00 AM.
         *
         * 9/15 at 06:30 is less than 24 hours away.
         * 9/16 at 06:30 is more than 24 hours away.
         */
        Booking inside24Hours =
                generatedBooking(
                        plan,
                        LocalDate.of(2026, 9, 15),
                        LocalTime.of(6, 30)
                );

        Booking laterBooking =
                generatedBooking(
                        plan,
                        LocalDate.of(2026, 9, 16),
                        LocalTime.of(6, 30)
                );

        when(setForgetPlanRepository
                .findByCustomerAndActiveTrue(customer))
                .thenReturn(Optional.of(plan));

        when(bookingRepository
                .findBySetForgetPlanAndDateGreaterThanEqualOrderByDateAsc(
                        plan,
                        LocalDate.of(2026, 9, 14)
                ))
                .thenReturn(
                        List.of(
                                inside24Hours,
                                laterBooking
                        )
                );

        int deleted =
                setForgetService.cancelPlan(customer);

        assertEquals(
                1,
                deleted
        );

        assertFalse(
                plan.isActive()
        );

        ArgumentCaptor<Iterable<Booking>> deletedCaptor =
                ArgumentCaptor.forClass(Iterable.class);

        verify(bookingRepository)
                .deleteAll(deletedCaptor.capture());

        List<Booking> deletedBookings =
                new java.util.ArrayList<>();

        deletedCaptor.getValue()
                .forEach(deletedBookings::add);

        assertEquals(
                1,
                deletedBookings.size()
        );

        assertTrue(
                deletedBookings.contains(laterBooking)
        );

        assertFalse(
                deletedBookings.contains(inside24Hours)
        );

        verify(setForgetPlanRepository)
                .save(plan);
    }

    @Test
    void addExceptionForBookingIfNeeded_createsOnceButNotDuplicate() {

        SetForgetPlan plan =
                activeTuesdayPlan();

        LocalDate bookingDate =
                LocalDate.of(2026, 9, 15);

        Booking booking =
                generatedBooking(
                        plan,
                        bookingDate,
                        LocalTime.of(6, 30)
                );

        when(setForgetExceptionRepository
                .existsByPlanAndExceptionDate(
                        plan,
                        bookingDate
                ))
                .thenReturn(false, true);

        setForgetService
                .addExceptionForBookingIfNeeded(
                        booking,
                        "CUSTOMER_CANCEL"
                );

        ArgumentCaptor<SetForgetException> captor =
                ArgumentCaptor.forClass(
                        SetForgetException.class
                );

        verify(setForgetExceptionRepository)
                .save(captor.capture());

        SetForgetException saved =
                captor.getValue();

        assertSame(
                plan,
                saved.getPlan()
        );

        assertEquals(
                bookingDate,
                saved.getExceptionDate()
        );

        assertEquals(
                "CUSTOMER_CANCEL",
                saved.getReason()
        );

        assertNotNull(
                saved.getCreatedAt()
        );

        /*
         * Simulate the same cancellation path being invoked again.
         * Repository now reports that the exception already exists.
         */
        setForgetService
                .addExceptionForBookingIfNeeded(
                        booking,
                        "CUSTOMER_CANCEL"
                );

        verify(
                setForgetExceptionRepository,
                times(1)
        ).save(any(SetForgetException.class));
    }

    private SetForgetRuleRequest rule(
            short dayOfWeek,
            String serviceType,
            String dropoffTime
    ) {

        SetForgetRuleRequest rule =
                new SetForgetRuleRequest();

        rule.setDayOfWeek(dayOfWeek);
        rule.setServiceType(serviceType);
        rule.setDropoffTime(dropoffTime);

        return rule;
    }

    private SetForgetPlan activeTuesdayPlan() {

        SetForgetPlan plan =
                new SetForgetPlan();

        plan.setId(200L);
        plan.setCustomer(customer);
        plan.setActive(true);
        plan.setStartDate(
                LocalDate.of(2026, 9, 14)
        );
        plan.setEndDate(
                LocalDate.of(2026, 12, 14)
        );
        plan.setDogCount(1);
        plan.setWantsAdvancePay(false);

        com.dogdaycare.model.SetForgetRule rule =
                new com.dogdaycare.model.SetForgetRule();

        rule.setId(300L);
        rule.setPlan(plan);
        rule.setDayOfWeek((short) 2);
        rule.setServiceType(
                "Daycare (6 AM - 8 PM)"
        );
        rule.setDropoffTime(
                LocalTime.of(6, 30)
        );
        rule.setActive(true);

        plan.getRules().add(rule);

        return plan;
    }

    private Booking generatedBooking(
            SetForgetPlan plan,
            LocalDate date,
            LocalTime time
    ) {

        Booking booking =
                new Booking();

        booking.setCustomer(customer);
        booking.setSetForgetPlan(plan);
        booking.setServiceType(
                "Daycare (6 AM - 8 PM)"
        );
        booking.setDate(date);
        booking.setTime(time);
        booking.setStatus("APPROVED");

        return booking;
    }
}