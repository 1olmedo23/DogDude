package com.dogdaycare.it;

import com.dogdaycare.dto.SetForgetRuleRequest;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.SetForgetService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dogdaycare.model.SetForgetPlan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Period;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:set_forget_edit_test;"
                + "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
@Transactional
class SetForgetPlanEditingIntegrationTest {

    @Autowired
    private SetForgetService setForgetService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private Clock clock;

    private User customer;

    @BeforeEach
    void setup() {
        // Monday, September 14, 2026, at 10 AM Pacific.
        when(clock.getZone())
                .thenReturn(ZoneId.of("America/Los_Angeles"));
        when(clock.instant())
                .thenReturn(Instant.parse("2026-09-14T17:00:00Z"));

        customer = new User();
        customer.setUsername("plan-edit-" + UUID.randomUUID() + "@test.local");
        customer.setPassword("{noop}test-only");
        customer.setRole("CUSTOMER");
        customer.setEnabled(true);
        customer = userRepository.saveAndFlush(customer);
    }

    @ParameterizedTest
    @CsvSource({
            "THREE_MONTHS, 3, false",
            "SIX_MONTHS, 6, false",
            "TWELVE_MONTHS, 12, false",
            "INDEFINITE, 12, true"
    })
    void planGeneratesEveryEligibleTuesdayThroughItsEndDate(
            String duration, int months, boolean autoRenew
    ) {
        SetForgetPlan plan = setForgetService.saveOrUpdatePlan(
                customer, true, 1, duration,
                List.of(rule((short) 2, "06:30"))
        );
        Long planId = plan.getId();
        flushAndClear();

        LocalDate expectedEnd =
                LocalDate.of(2026, 9, 14).plusMonths(months);

        SetForgetPlan stored =
                entityManager.find(SetForgetPlan.class, planId);

        assertEquals(expectedEnd, stored.getEndDate());
        assertEquals(autoRenew, stored.isAutoRenew());
        assertEquals(duration, stored.getDurationOption());

        // September 15 is inside the 24-hour cutoff.
        // The first eligible Tuesday is September 22.
        List<LocalDate> expectedDates = LocalDate.of(2026, 9, 22)
                .datesUntil(expectedEnd.plusDays(1), Period.ofWeeks(1))
                .toList();

        // This helper also fails if any date has duplicate bookings.
        List<LocalDate> actualDates = bookingIdsByDate().keySet().stream()
                .sorted()
                .toList();

        assertEquals(expectedDates, actualDates);
    }

    @ParameterizedTest
    @CsvSource({
            "THREE_MONTHS, false",
            "SIX_MONTHS, false",
            "TWELVE_MONTHS, false",
            "INDEFINITE, true"
    })
    void renewalExtendsOnlyIndefinite_andDoesNotDuplicateBookings(
            String duration, boolean autoRenew
    ) {
        SetForgetPlan plan = setForgetService.saveOrUpdatePlan(
                customer, true, 1, duration,
                List.of(rule((short) 3, "06:30"))
        );

        Long planId = plan.getId();
        LocalDate originalEnd = plan.getEndDate();
        Map<LocalDate, Long> originalIds = bookingIdsByDate();
        Map<Long, BigDecimal> originalPrices = bookingPricesById();
        flushAndClear();

        ZoneId zone = ZoneId.of("America/Los_Angeles");

        // The day before the end date: do not renew yet.
        when(clock.instant()).thenReturn(
                originalEnd.minusDays(1).atStartOfDay(zone).toInstant()
        );

        assertEquals(
                0,
                setForgetService.generateBookingsForActivePlan(customer)
        );
        flushAndClear();

        assertEquals(
                originalEnd,
                entityManager.find(SetForgetPlan.class, planId).getEndDate()
        );
        flushAndClear();

        // Move the test clock to midnight on the end date.
        when(clock.instant()).thenReturn(
                originalEnd.atStartOfDay(zone).toInstant()
        );

        int created =
                setForgetService.generateBookingsForActivePlan(customer);
        flushAndClear();

        LocalDate expectedEnd =
                autoRenew ? originalEnd.plusYears(1) : originalEnd;

        assertEquals(
                expectedEnd,
                entityManager.find(SetForgetPlan.class, planId).getEndDate()
        );
        assertEquals(autoRenew, created > 0);

        Map<LocalDate, Long> afterRenewal = bookingIdsByDate();
        Map<Long, BigDecimal> afterPrices = bookingPricesById();

        originalIds.forEach((date, id) ->
                assertEquals(id, afterRenewal.get(date)));
        originalPrices.forEach((id, price) ->
                assertEquals(price, afterPrices.get(id)));

        if (autoRenew) {
            // For this annual plan, the old end date is Tuesday.
            // Wednesday at 6:30 AM is more than 24 hours away.
            List<LocalDate> expectedNewDates = originalEnd.plusDays(1)
                    .datesUntil(expectedEnd.plusDays(1), Period.ofWeeks(1))
                    .toList();

            List<LocalDate> actualNewDates = afterRenewal.keySet().stream()
                    .filter(date -> date.isAfter(originalEnd))
                    .sorted()
                    .toList();

            assertEquals(expectedNewDates, actualNewDates);
            assertEquals(expectedNewDates.size(), created);
        } else {
            assertEquals(originalIds, afterRenewal);
        }

        // A repeated check must not renew twice or duplicate bookings.
        assertEquals(
                0,
                setForgetService.generateBookingsForActivePlan(customer)
        );
        flushAndClear();

        assertEquals(afterRenewal, bookingIdsByDate());
        assertEquals(
                expectedEnd,
                entityManager.find(SetForgetPlan.class, planId).getEndDate()
        );
    }

    @Test
    void savingSamePlanTwice_preservesBookingsAndManualReservation() {
        LocalDate manualDate = LocalDate.of(2026, 9, 23);

        Booking manual = new Booking();
        manual.setCustomer(customer);
        manual.setDate(manualDate);
        manual.setTime(LocalTime.of(9, 0));
        manual.setServiceType("Daycare (6 AM - 3 PM)");
        manual.setStatus("APPROVED");
        manual.setDogCount(1);
        manual.setCreatedAt(LocalDateTime.now(clock));
        manual.setQuotedRateAtLock(new BigDecimal("50.00"));

        Long manualId = bookingRepository.saveAndFlush(manual).getId();

        savePlan(List.of(
                rule((short) 2, "06:30"),
                rule((short) 3, "06:30")
        ));
        flushAndClear();

        Map<LocalDate, Long> originalIds = bookingIdsByDate();
        Map<Long, BigDecimal> originalPrices = bookingPricesById();

        assertTrue(originalIds.size() > 1);
        assertEquals(manualId, originalIds.get(manualDate));

        savePlan(List.of(
                rule((short) 2, "06:30"),
                rule((short) 3, "06:30")
        ));
        flushAndClear();

        assertEquals(originalIds, bookingIdsByDate());
        assertEquals(originalPrices, bookingPricesById());

        Booking preservedManual = bookingOn(manualDate);

        assertEquals(manualId, preservedManual.getId());
        assertNull(preservedManual.getSetForgetPlan());
        assertEquals(
                "Daycare (6 AM - 3 PM)",
                preservedManual.getServiceType()
        );
        assertEquals(LocalTime.of(9, 0), preservedManual.getTime());
    }

    @Test
    void editingDropoffTime_replacesUnpaidBooking_butPreservesPaidBooking() {
        savePlan(List.of(rule((short) 2, "06:30")));
        flushAndClear();

        LocalDate paidDate = LocalDate.of(2026, 9, 22);
        LocalDate unpaidDate = LocalDate.of(2026, 9, 29);

        Booking paid = bookingOn(paidDate);
        Long paidId = paid.getId();
        BigDecimal paidPrice = paid.getQuotedRateAtLock();
        LocalDateTime paidAt = LocalDateTime.now(clock);

        paid.setPaid(true);
        paid.setPaidAt(paidAt);
        bookingRepository.saveAndFlush(paid);

        Long originalUnpaidId = bookingOn(unpaidDate).getId();
        int originalCount = bookingIdsByDate().size();

        flushAndClear();

        // Change Tuesdays from 6:30 AM to 8 AM.
        savePlan(List.of(rule((short) 2, "08:00")));
        flushAndClear();

        Booking preservedPaid = bookingOn(paidDate);
        Booking replacement = bookingOn(unpaidDate);

        assertEquals(paidId, preservedPaid.getId());
        assertTrue(preservedPaid.isPaid());
        assertEquals(paidAt, preservedPaid.getPaidAt());
        assertEquals(paidPrice, preservedPaid.getQuotedRateAtLock());
        assertEquals(LocalTime.of(6, 30), preservedPaid.getTime());

        assertNotEquals(originalUnpaidId, replacement.getId());
        assertFalse(replacement.isPaid());
        assertEquals(LocalTime.of(8, 0), replacement.getTime());
        assertNotNull(replacement.getSetForgetPlan());
        assertNotNull(replacement.getQuotedRateAtLock());

        assertTrue(bookingRepository.findById(originalUnpaidId).isEmpty());

        // Same number of dates, with no duplicate customer/date records.
        assertEquals(originalCount, bookingIdsByDate().size());
    }

    private void savePlan(List<SetForgetRuleRequest> rules) {
        setForgetService.saveOrUpdatePlan(
                customer,
                true,
                1,
                "THREE_MONTHS",
                rules
        );
    }

    private SetForgetRuleRequest rule(short weekday, String time) {
        SetForgetRuleRequest request = new SetForgetRuleRequest();
        request.setDayOfWeek(weekday);
        request.setServiceType("Daycare (6 AM - 8 PM)");
        request.setDropoffTime(time);
        return request;
    }

    private Booking bookingOn(LocalDate date) {
        List<Booking> bookings =
                bookingRepository.findByCustomerAndDate(customer, date);

        assertEquals(
                1,
                bookings.size(),
                "Expected exactly one reservation for " + date
        );

        return bookings.get(0);
    }

    private Map<LocalDate, Long> bookingIdsByDate() {
        // Duplicate dates cause this collection to fail the test.
        return bookingRepository.findByCustomer(customer).stream()
                .collect(Collectors.toMap(
                        Booking::getDate,
                        Booking::getId
                ));
    }

    private Map<Long, BigDecimal> bookingPricesById() {
        return bookingRepository.findByCustomer(customer).stream()
                .collect(Collectors.toMap(
                        Booking::getId,
                        Booking::getQuotedRateAtLock
                ));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}