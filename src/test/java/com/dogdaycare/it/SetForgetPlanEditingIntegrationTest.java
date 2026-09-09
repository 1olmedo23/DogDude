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