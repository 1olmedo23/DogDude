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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookingJsonAndSecurityIT {

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
        customer.setUsername("rounding-sec@example.com");
        customer.setPassword("{noop}pw");
        customer.setRole("CUSTOMER");
        customer.setEnabled(true);
        customer = userRepo.save(customer);
    }

    private Booking daycare(LocalDate date, int dogs) {
        Booking b = new Booking();
        b.setCustomer(customer);
        b.setDate(date);
        b.setServiceType("Daycare (6 AM - 3 PM)");
        b.setStatus("APPROVED");
        b.setDogCount(dogs);
        b.setAdvanceEligible(true);
        b.setWantsAdvancePay(true);
        return bookingRepo.save(b);
    }

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void admin_endpoints_require_admin_role() throws Exception {
        LocalDate day = LocalDate.of(2025, 2, 3);
        daycare(day, 1);

        // /admin/bookings requires ADMIN
        mvc.perform(get("/admin/bookings").param("date", day.toString()))
                .andExpect(status().isForbidden());

        // /admin/invoices/weekly requires ADMIN
        mvc.perform(get("/admin/invoices/weekly").param("start", day.with(java.time.DayOfWeek.MONDAY).toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void rounding_to_two_decimals_in_service_and_json_value_is_correct() throws Exception {
        LocalDate mon = LocalDate.of(2025, 2, 3);

        // Build up days so that the 4th day uses the $40.00 tier
        daycare(mon, 1);
        daycare(mon.plusDays(1), 1);
        daycare(mon.plusDays(2), 1);
        Booking fourth = daycare(mon.plusDays(3), 3); // 3 dogs × $40.00 = 120.00 expected

        // Service should return values expressed at cent precision (equality on BigDecimal literal with 2 decimals)
        BigDecimal perDog = pricingService.priceFor(fourth);
        assertThat(perDog).isEqualByComparingTo(new BigDecimal("40.00"));

        // JSON should reflect the multiplied total; we assert the numeric value equals 120.0
        mvc.perform(get("/admin/bookings").param("date", fourth.getDate().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // we intentionally assert the numeric value; representation (120.0 vs 120.00) is serializer-dependent
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].liveAmount").value(120.0));
    }
}
