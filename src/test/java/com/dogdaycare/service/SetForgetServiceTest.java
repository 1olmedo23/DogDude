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
         * The fixed date is Monday 9/14/2026.
         * Tuesday's next occurrence is 9/15/2026.
         */
        LocalDate firstBookingDate =
                LocalDate.of(2026, 9, 15);

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
                LocalDate.of(2026, 9, 15);

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