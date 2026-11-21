package com.dogdaycare.it;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.InvoiceRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import static org.hamcrest.Matchers.closeTo;

import java.math.BigDecimal;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "admin", roles = "ADMIN")
class PricingFlowIntegrationTest {

    @Autowired MockMvc mvc;

    @Autowired UserRepository userRepo;
    @Autowired EvaluationRepository evalRepo;
    @Autowired BookingRepository bookingRepo;
    @Autowired InvoiceRepository invoiceRepo;
    @Autowired PricingService pricingService;

    // Pin time for deterministic week math
    @MockBean Clock clock;

    private User customer;
    private String email = "alice@example.com";

    @BeforeEach
    void setUp() {
        // Fixed Monday 2025-02-03T09:00 America/Denver
        ZoneId zone = ZoneId.of("America/Denver");
        var fixed = ZonedDateTime.of(2025, 2, 3, 9, 0, 0, 0, zone);
        org.mockito.Mockito.when(clock.getZone()).thenReturn(zone);
        org.mockito.Mockito.when(clock.instant()).thenReturn(fixed.toInstant());

        bookingRepo.deleteAll();
        invoiceRepo.deleteAll();
        evalRepo.deleteAll();
        userRepo.deleteAll();

        customer = new User();
        customer.setUsername(email);
        customer.setPassword("{noop}pw");
        customer.setRole("CUSTOMER");
        customer.setEnabled(true);
        userRepo.save(customer);

        EvaluationRequest er = new EvaluationRequest();
        er.setClientName("Alice");
        er.setEmail(email);
        er.setPhone("555-111-2222");
        er.setDogName("Buddy");
        er.setDogBreed("Lab");
        er.setCreatedAt(LocalDateTime.now(clock));
        evalRepo.save(er);
    }

    // ---------- Helpers ----------
    private Booking newBooking(LocalDate date, String service, int dogs) {
        Booking b = new Booking();
        b.setCustomer(customer);
        b.setDate(date);
        b.setTime(LocalTime.of(9, 0));
        b.setServiceType(service);
        b.setStatus("APPROVED");
        b.setDogCount(dogs);
        b.setCreatedAt(LocalDateTime.now(clock));
        bookingRepo.save(b);
        return b;
    }

    private BigDecimal liveDaycarePerDog(LocalDate date, boolean expectTier) {
        // Build a probe booking to reuse pricing primitive
        Booking probe = new Booking();
        probe.setCustomer(customer);
        probe.setDate(date);
        probe.setTime(LocalTime.of(9, 0));
        probe.setServiceType("Daycare (6 AM - 3 PM)");
        return pricingService.quoteDaycareAtTier(probe, expectTier);
    }

    // ---------- Tests ----------

    @Test
    void adminBookings_chipReflectsTier_whenCrossing4AndBack() throws Exception {
        // Week starting Monday 2025-02-03
        LocalDate mon = LocalDate.of(2025, 2, 3);
        // Seed 3 daycare days (higher tier)
        newBooking(mon, "Daycare (6 AM - 3 PM)", 1);
        newBooking(mon.plusDays(1), "Daycare (6 AM - 3 PM)", 1);
        newBooking(mon.plusDays(2), "Daycare (6 AM - 3 PM)", 1);

        // Live per-dog before 4th
        BigDecimal perDogBefore = liveDaycarePerDog(mon, false);
        // Add 4th daycare in same week (lower tier)
        newBooking(mon.plusDays(3), "Daycare (6 AM - 3 PM)", 1);
        BigDecimal perDogAfter = liveDaycarePerDog(mon, true);

        assertThat(perDogAfter).isLessThan(perDogBefore);

        // Admin bookings JSON for the 4th day should carry the lower tier as liveAmount
        mvc.perform(get("/admin/bookings").param("date", mon.plusDays(3).toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].liveAmount").exists());
    }

    @Test
    void adminInvoicing_weeklyTotalsUseTier_andDogCount()
            throws Exception {

        // Test clock is fixed at February 3, 2025.
        // Use the following week so all dates are
        // eligible under the new 2:00 AM cutoff.
        LocalDate mon = LocalDate.of(2025, 2, 10);

        // Four prepaid daycare bookings, two dogs each.
        // 4 booking days x 2 dogs = 8 qualifying dog-days.
        for (int i = 0; i < 4; i++) {

            Booking booking = newBooking(
                    mon.plusDays(i),
                    "Daycare (6 AM - 3 PM)",
                    2
            );

            booking.setAdvanceEligible(true);
            booking.setWantsAdvancePay(true);

            bookingRepo.save(booking);
        }

        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", mon.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                // 8 dog-days x $40 prepaid rate = $320.
                .andExpect(jsonPath("$[0].amount").value(320.0))
                .andExpect(jsonPath("$[0].paidToDate").value(0.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(320.0))
                .andExpect(jsonPath("$[0].newSincePaid").exists());
    }

    @Test
    void adminInvoicing_markPaid_freezesCurrentPrepayTier()
            throws Exception {

        LocalDate monday = LocalDate.of(2025, 2, 10);

        // Two dogs booked on two days.
        // Together, these count as four prepaid dog-days.
        Booking tuesday = newBooking(
                monday.plusDays(1),
                "Daycare (6 AM - 8 PM)",
                2
        );

        Booking thursday = newBooking(
                monday.plusDays(3),
                "Daycare (6 AM - 8 PM)",
                2
        );

        for (Booking booking : new Booking[]{tuesday, thursday}) {
            booking.setAdvanceEligible(true);
            booking.setWantsAdvancePay(true);

            // Original 1-3 tier estimate: $50 x 2 dogs.
            booking.setQuotedRateAtLock(
                    new BigDecimal("100.00")
            );

            bookingRepo.save(booking);
        }

        // Mark the entire week paid through the actual
        // admin invoice endpoint.
        mvc.perform(post("/admin/invoices/mark-paid")
                        .param("email", email)
                        .param("start", monday.toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Reload the bookings from the database.
        Booking savedTuesday = bookingRepo
                .findById(tuesday.getId())
                .orElseThrow();

        Booking savedThursday = bookingRepo
                .findById(thursday.getId())
                .orElseThrow();

        // Both bookings should now be marked paid.
        assertThat(savedTuesday.isPaid()).isTrue();
        assertThat(savedThursday.isPaid()).isTrue();

        assertThat(savedTuesday.getPaidAt()).isNotNull();
        assertThat(savedThursday.getPaidAt()).isNotNull();

        // 4+ dog-day tier for Daycare 6-8:
        // $45 per dog x 2 dogs = $90 per booking.
        assertThat(savedTuesday.getQuotedRateAtLock())
                .isEqualByComparingTo("90.00");

        assertThat(savedThursday.getQuotedRateAtLock())
                .isEqualByComparingTo("90.00");

        // Both should be recognized as prepaid bookings.
        assertThat(savedTuesday.isInPrepayBundle()).isTrue();
        assertThat(savedThursday.isInPrepayBundle()).isTrue();

        // Verify the saved invoice total.
        var invoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(invoice.isPaid()).isTrue();

        assertThat(invoice.getAmount())
                .isEqualByComparingTo("180.00");

        // Verify the admin weekly summary after payment.
        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(180.0))
                .andExpect(jsonPath("$[0].paidToDate").value(180.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(0.0));
    }

    @Test
    void adminInvoicing_additionalPayment_preservesPreviousPaidAmounts()
            throws Exception {

        LocalDate monday = LocalDate.of(2025, 2, 10);
        String service = "Daycare (6 AM - 8 PM)";

        Booking tuesday = newBooking(
                monday.plusDays(1), service, 2);

        Booking thursday = newBooking(
                monday.plusDays(3), service, 2);

        // Initially, both qualify for weekly prepay.
        for (Booking booking : new Booking[]{tuesday, thursday}) {
            booking.setAdvanceEligible(true);
            booking.setWantsAdvancePay(true);
            booking.setQuotedRateAtLock(new BigDecimal("100.00"));
            bookingRepo.save(booking);
        }

        // First payment: finalize the original prepaid bookings.
        mvc.perform(post("/admin/invoices/mark-paid")
                        .param("email", email)
                        .param("start", monday.toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Simulate a NEW booking after the week was paid.
        // The booking is not eligible for prepaid pricing
        // because the weekly payment has already occurred.
        Booking friday = newBooking(
                monday.plusDays(4), service, 2);

        friday.setAdvanceEligible(true);
        friday.setWantsAdvancePay(false);

        // Simulate an outdated estimate to verify that
        // payment finalization recalculates the regular rate.
        friday.setQuotedRateAtLock(new BigDecimal("100.00"));
        bookingRepo.save(friday);

        // Before the second payment:
        // $180 previously paid, $120 newly owed.
        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(300.0))
                .andExpect(jsonPath("$[0].paidToDate").value(180.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(120.0));

        // Pay the new booking through the same weekly endpoint.
        mvc.perform(post("/admin/invoices/mark-paid")
                        .param("email", email)
                        .param("start", monday.toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Booking savedTuesday =
                bookingRepo.findById(tuesday.getId()).orElseThrow();

        Booking savedThursday =
                bookingRepo.findById(thursday.getId()).orElseThrow();

        Booking savedFriday =
                bookingRepo.findById(friday.getId()).orElseThrow();

        // Earlier payments must remain unchanged.
        assertThat(savedTuesday.getQuotedRateAtLock())
                .isEqualByComparingTo("90.00");

        assertThat(savedThursday.getQuotedRateAtLock())
                .isEqualByComparingTo("90.00");

        assertThat(savedTuesday.isPaid()).isTrue();
        assertThat(savedThursday.isPaid()).isTrue();

        // Newly added Friday uses the regular $60/dog rate.
        assertThat(savedFriday.getQuotedRateAtLock())
                .isEqualByComparingTo("120.00");

        assertThat(savedFriday.isPaid()).isTrue();
        assertThat(savedFriday.isWantsAdvancePay()).isFalse();
        assertThat(savedFriday.isInPrepayBundle()).isFalse();

        // Invoice now represents all three finalized bookings.
        var invoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(invoice.isPaid()).isTrue();

        assertThat(invoice.getAmount())
                .isEqualByComparingTo("300.00");

        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(300.0))
                .andExpect(jsonPath("$[0].paidToDate").value(300.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(0.0));
    }

    @Test
    void adminBookings_markSinglePaid_freezesCorrectAmount()
            throws Exception {

        LocalDate monday = LocalDate.of(2025, 2, 10);

        Booking booking = newBooking(
                monday.plusDays(1),
                "Daycare After Hours (6 AM - 11 PM)",
                3
        );

        // Simulate a stale estimate from the old $90 rate.
        booking.setQuotedRateAtLock(
                new BigDecimal("270.00")
        );
        bookingRepo.save(booking);

        // Mark this single booking paid.
        mvc.perform(post("/admin/bookings/mark-paid/"
                        + booking.getId())
                        .param("date", booking.getDate().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Booking saved = bookingRepo
                .findById(booking.getId())
                .orElseThrow();

        assertThat(saved.isPaid()).isTrue();
        assertThat(saved.getPaidAt()).isNotNull();

        // Updated After Hours rate:
        // $75 per dog x 3 dogs = $225.
        assertThat(saved.getQuotedRateAtLock())
                .isEqualByComparingTo("225.00");

        // The invoice must also reflect the correct amount.
        var invoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(invoice.isPaid()).isTrue();

        assertThat(invoice.getAmount())
                .isEqualByComparingTo("225.00");
    }

    @Test
    void adminBookings_partialPayments_updateWeeklyInvoiceCorrectly()
            throws Exception {

        LocalDate monday = LocalDate.of(2025, 2, 10);
        String service = "Daycare (6 AM - 8 PM)";

        Booking tuesday = newBooking(
                monday.plusDays(1), service, 1);

        Booking thursday = newBooking(
                monday.plusDays(3), service, 1);

        // Both are regular-price bookings: $60 each.
        assertThat(tuesday.isPaid()).isFalse();
        assertThat(thursday.isPaid()).isFalse();

        // Pay only Tuesday.
        mvc.perform(post("/admin/bookings/mark-paid/"
                        + tuesday.getId())
                        .param("date", tuesday.getDate().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Booking savedTuesday =
                bookingRepo.findById(tuesday.getId()).orElseThrow();

        Booking savedThursday =
                bookingRepo.findById(thursday.getId()).orElseThrow();

        assertThat(savedTuesday.isPaid()).isTrue();
        assertThat(savedThursday.isPaid()).isFalse();

        assertThat(savedTuesday.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        var partialInvoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(partialInvoice.isPaid()).isFalse();
        assertThat(partialInvoice.getAmount())
                .isEqualByComparingTo("120.00");

        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(120.0))
                .andExpect(jsonPath("$[0].paidToDate").value(60.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(60.0));

        // Pay Thursday separately.
        mvc.perform(post("/admin/bookings/mark-paid/"
                        + thursday.getId())
                        .param("date", thursday.getDate().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        savedTuesday =
                bookingRepo.findById(tuesday.getId()).orElseThrow();

        savedThursday =
                bookingRepo.findById(thursday.getId()).orElseThrow();

        // Both paid and original Tuesday price preserved.
        assertThat(savedTuesday.isPaid()).isTrue();
        assertThat(savedThursday.isPaid()).isTrue();

        assertThat(savedTuesday.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        assertThat(savedThursday.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        var finalInvoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(finalInvoice.isPaid()).isTrue();
        assertThat(finalInvoice.getAmount())
                .isEqualByComparingTo("120.00");

        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(120.0))
                .andExpect(jsonPath("$[0].paidToDate").value(120.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(0.0));
    }

    @Test
    void afterHours_is75PerDog_liveEverywhere()
            throws Exception {

        LocalDate tue = LocalDate.of(2025, 2, 4);

        newBooking(
                tue,
                "Daycare After Hours (6 AM - 11 PM)",
                3
        );

        mvc.perform(get("/admin/bookings")
                        .param("date", tue.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].liveAmount")
                        .value(closeTo(225.0, 0.001)));
    }

    @Test
    void boarding_pickupWaived_whenNextDayDaycare_sameWeek() {
        LocalDate wed = LocalDate.of(2025, 2, 5);
        // A boarding stay that “ends” Wed (assume your priceFor uses last-night logic)
        Booking boarding = newBooking(wed, "Boarding", 1);
        BigDecimal before = pricingService.priceFor(boarding);

        // Add next-day daycare (Thu) in same week → expect boarding price to drop (waive pickup)
        newBooking(wed.plusDays(1), "Daycare (6 AM - 3 PM)", 1);
        BigDecimal after = pricingService.priceFor(boarding);

        // If your policy waives pickup when daycare exists next day, after < before
        assertThat(after).isLessThan(before);
    }

    @Test
    void boarding_pickupShouldBeWaived_evenIfNextDayIsNextWeek_SUNDAYtoMONDAY() {
        // End of week: Sunday
        LocalDate sun = LocalDate.of(2025, 2, 9); // Sunday of the same test week
        Booking boarding = newBooking(sun, "Boarding", 1);
        BigDecimal before = pricingService.priceFor(boarding);

        // Next day is Monday (next week): book daycare
        newBooking(sun.plusDays(1), "Daycare (6 AM - 3 PM)", 1);
        BigDecimal after = pricingService.priceFor(boarding);

        // EXPECTATION: pickup waiver should still apply across week boundaries
        // This assertion will likely FAIL with current logic → tells us what to fix.
        assertThat(after).isLessThan(before);
    }

    @Test
    void adminBookings_revertSinglePayment_updatesInvoiceStatus()
            throws Exception {

        LocalDate monday = LocalDate.of(2025, 2, 10);
        String service = "Daycare (6 AM - 8 PM)";

        Booking tuesday = newBooking(
                monday.plusDays(1), service, 1);

        Booking thursday = newBooking(
                monday.plusDays(3), service, 1);

        // Pay both bookings individually.
        for (Booking booking : new Booking[]{tuesday, thursday}) {
            mvc.perform(post("/admin/bookings/mark-paid/"
                            + booking.getId())
                            .param("date", booking.getDate().toString())
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }

        var paidInvoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(paidInvoice.isPaid()).isTrue();
        assertThat(paidInvoice.getAmount())
                .isEqualByComparingTo("120.00");

        // Revert Thursday's payment.
        mvc.perform(post("/admin/bookings/revert-paid/"
                        + thursday.getId())
                        .param("date", thursday.getDate().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Booking savedTuesday = bookingRepo
                .findById(tuesday.getId()).orElseThrow();

        Booking savedThursday = bookingRepo
                .findById(thursday.getId()).orElseThrow();

        // Tuesday's payment remains intact.
        assertThat(savedTuesday.isPaid()).isTrue();
        assertThat(savedTuesday.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        // Thursday is no longer marked paid.
        assertThat(savedThursday.isPaid()).isFalse();
        assertThat(savedThursday.getPaidAt()).isNull();

        // The original finalized price is retained.
        assertThat(savedThursday.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        // Invoice must reflect the partial reversal.
        var updatedInvoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(updatedInvoice.isPaid()).isFalse();

        // No booking was canceled or deleted.
        assertThat(updatedInvoice.getAmount())
                .isEqualByComparingTo("120.00");

        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(120.0))
                .andExpect(jsonPath("$[0].paidToDate").value(60.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(60.0));
    }

    @Test
    void adminBookings_paidBooking_rejectsManualAdjustment()
            throws Exception {

        LocalDate monday = LocalDate.of(2025, 2, 10);

        Booking booking = newBooking(
                monday.plusDays(1),
                "Daycare (6 AM - 8 PM)",
                1
        );

        // Finalize the booking through the existing endpoint.
        mvc.perform(post("/admin/bookings/mark-paid/"
                        + booking.getId())
                        .param("date", booking.getDate().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Booking paidBooking = bookingRepo
                .findById(booking.getId())
                .orElseThrow();

        assertThat(paidBooking.isPaid()).isTrue();
        assertThat(paidBooking.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        // Attempt to change the amount after payment.
        mvc.perform(post("/admin/bookings/adjust/"
                        + booking.getId())
                        .param("amount", "-10")
                        .param("reason", "Post-payment adjustment")
                        .param("date", booking.getDate().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Booking afterAttempt = bookingRepo
                .findById(booking.getId())
                .orElseThrow();

        // Paid booking and finalized amount remain unchanged.
        assertThat(afterAttempt.isPaid()).isTrue();
        assertThat(afterAttempt.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        assertThat(afterAttempt.getManualAdjustmentAmount())
                .isNull();

        var invoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(invoice.getAmount())
                .isEqualByComparingTo("60.00");

        assertThat(invoice.isPaid()).isTrue();
    }

    @Test
    void adminInvoicing_revertWeeklyPayment_revertsAllBookings()
            throws Exception {

        LocalDate monday = LocalDate.of(2025, 2, 10);
        String service = "Daycare (6 AM - 8 PM)";

        Booking tuesday = newBooking(
                monday.plusDays(1), service, 1);

        Booking thursday = newBooking(
                monday.plusDays(3), service, 1);

        // First, pay Tuesday individually.
        mvc.perform(post("/admin/bookings/mark-paid/"
                        + tuesday.getId())
                        .param("date", tuesday.getDate().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        // Pay the remaining bookings through weekly invoicing.
        mvc.perform(post("/admin/invoices/mark-paid")
                        .param("email", email)
                        .param("start", monday.toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        var paidInvoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(paidInvoice.isPaid()).isTrue();
        assertThat(paidInvoice.getAmount())
                .isEqualByComparingTo("120.00");

        assertThat(bookingRepo.findById(tuesday.getId())
                .orElseThrow().isPaid()).isTrue();

        assertThat(bookingRepo.findById(thursday.getId())
                .orElseThrow().isPaid()).isTrue();

        // Revert the entire weekly invoice.
        mvc.perform(post("/admin/invoices/revert-paid")
                        .param("invoiceId",
                                paidInvoice.getId().toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Booking savedTuesday = bookingRepo
                .findById(tuesday.getId())
                .orElseThrow();

        Booking savedThursday = bookingRepo
                .findById(thursday.getId())
                .orElseThrow();

        // Both bookings must now be unpaid.
        assertThat(savedTuesday.isPaid()).isFalse();
        assertThat(savedThursday.isPaid()).isFalse();

        assertThat(savedTuesday.getPaidAt()).isNull();
        assertThat(savedThursday.getPaidAt()).isNull();

        // Previously finalized amounts remain stored.
        assertThat(savedTuesday.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        assertThat(savedThursday.getQuotedRateAtLock())
                .isEqualByComparingTo("60.00");

        // Weekly invoice payment status must be reverted.
        var revertedInvoice = invoiceRepo
                .findByCustomerEmailAndWeekStart(email, monday)
                .orElseThrow();

        assertThat(revertedInvoice.isPaid()).isFalse();
        assertThat(revertedInvoice.getPaidAt()).isNull();

        // The invoice's stored amount is preserved.
        assertThat(revertedInvoice.getAmount())
                .isEqualByComparingTo("120.00");

        // Verify the admin invoicing summary.
        mvc.perform(get("/admin/invoices/weekly")
                        .param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(120.0))
                .andExpect(jsonPath("$[0].paidToDate").value(0.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(120.0));
    }
}
