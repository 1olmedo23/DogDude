package com.dogdaycare.it;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin", roles = "ADMIN")
@Transactional
class PricingEdgeCasesIT {

    @Autowired MockMvc mvc;

    @Autowired PricingService pricingService;
    @Autowired BookingRepository bookingRepo;
    @Autowired UserRepository userRepo;

    private User customer;

    @BeforeEach
    void setup() {
        bookingRepo.deleteAll();
        userRepo.deleteAll();

        customer = new User();
        customer.setUsername("edge@example.com");
        customer.setPassword("{noop}pw");
        customer.setRole("CUSTOMER");
        customer.setEnabled(true);
        customer = userRepo.save(customer);
    }

    private Booking newBooking(LocalDate date, String serviceType, int dogs, String status) {
        Booking b = new Booking();
        b.setCustomer(customer);
        b.setDate(date);
        b.setServiceType(serviceType);
        b.setStatus(status);
        b.setDogCount(dogs);
        return bookingRepo.save(b);
    }

    @Test
    void canceledMondayDaycare_doesNotWaivePickup() {
        LocalDate sun = LocalDate.of(2025, 10, 19); // Sunday
        Booking sundayBoarding = newBooking(sun, "Boarding", 1, "APPROVED");

        BigDecimal before = pricingService.priceFor(sundayBoarding); // expect 135.00 with empty next day
        // Add Monday daycare BUT canceled
        newBooking(sun.plusDays(1), "Daycare (6 AM - 3 PM)", 1, "CANCELED");

        BigDecimal after = pricingService.priceFor(sundayBoarding);
        assertThat(after).isEqualByComparingTo(before); // waiver should NOT apply
        assertThat(after).isEqualByComparingTo(new BigDecimal("135.00"));
    }

    @Test
    void afterHoursMondayDaycare_doesNotWaivePickup() {
        LocalDate sun = LocalDate.of(2025, 10, 19); // Sunday
        Booking sundayBoarding = newBooking(sun, "Boarding", 1, "APPROVED");

        BigDecimal before = pricingService.priceFor(sundayBoarding); // 135.00 baseline

        // Monday Daycare After Hours (should not trigger waiver)
        newBooking(sun.plusDays(1), "Daycare After Hours", 1, "APPROVED");

        BigDecimal after = pricingService.priceFor(sundayBoarding);
        assertThat(after).isEqualByComparingTo(before); // still 135.00
        assertThat(after).isEqualByComparingTo(new BigDecimal("135.00"));
    }

    @Test
    void twoDogBoarding_multipliesInAdminBookingsJson() throws Exception {
        LocalDate sun = LocalDate.of(2025, 10, 19); // Sunday
        newBooking(sun, "Boarding", 2, "APPROVED");

        // No Monday daycare → pickup applies → 135 per dog × 2 = 270.00
        mvc.perform(get("/admin/bookings").param("date", sun.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].serviceType").value(org.hamcrest.Matchers.containsStringIgnoringCase("boarding")))
                .andExpect(jsonPath("$[0].dogCount").value(2))
                .andExpect(jsonPath("$[0].liveAmount").value(270.0));
    }
}
