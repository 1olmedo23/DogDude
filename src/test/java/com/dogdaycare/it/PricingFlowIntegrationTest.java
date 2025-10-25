package com.dogdaycare.it;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EvaluationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void adminInvoicing_weeklyTotalsUseTier_andDogCount() throws Exception {
        LocalDate mon = LocalDate.of(2025, 2, 3);
        // 3 daycare × 2 dogs → higher tier
        newBooking(mon, "Daycare (6 AM - 3 PM)", 2);
        newBooking(mon.plusDays(1), "Daycare (6 AM - 3 PM)", 2);
        newBooking(mon.plusDays(2), "Daycare (6 AM - 3 PM)", 2);
        // Add 4th day (still ×2) → lower tier should apply to totals
        newBooking(mon.plusDays(3), "Daycare (6 AM - 3 PM)", 2);

        mvc.perform(get("/admin/invoices/weekly").param("start", mon.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].amount").value(320.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(320.0))
                .andExpect(jsonPath("$[0].newSincePaid").exists());
    }

    @Test
    void afterHours_is90PerDog_liveEverywhere() throws Exception {
        LocalDate tue = LocalDate.of(2025, 2, 4);
        newBooking(tue, "Daycare After Hours (6 AM - 11 PM)", 3);

        mvc.perform(get("/admin/bookings").param("date", tue.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].liveAmount").value(closeTo(270.0, 0.001))); // 90×3
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
}
