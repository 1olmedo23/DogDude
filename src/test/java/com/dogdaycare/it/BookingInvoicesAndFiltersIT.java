package com.dogdaycare.it;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.UserRepository;
import org.hamcrest.Matchers;
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
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "admin", roles = "ADMIN")
class BookingInvoicesAndFiltersIT {

    @Autowired MockMvc mvc;
    @Autowired BookingRepository bookingRepo;
    @Autowired UserRepository userRepo;

    private User customer;

    @BeforeEach
    void setup() {
        bookingRepo.deleteAll();
        userRepo.deleteAll();

        customer = new User();
        customer.setUsername("invoice@example.com");
        customer.setPassword("{noop}pw");
        customer.setRole("CUSTOMER");
        customer.setEnabled(true);
        customer = userRepo.save(customer);
    }

    private Booking daycare(LocalDate date, int dogs, boolean paid) {
        Booking b = new Booking();
        b.setCustomer(customer);
        b.setDate(date);
        b.setServiceType("Daycare (6 AM - 3 PM)");
        b.setStatus("APPROVED");
        b.setDogCount(dogs);
        b.setAdvanceEligible(true);
        b.setWantsAdvancePay(true);
        // lock to a stable price so invoice math is deterministic
        b.setQuotedRateAtLock(new BigDecimal("45.00"));
        if (paid) {
            b.setPaid(true);
            b.setPaidAt(LocalDateTime.now());
        }
        return bookingRepo.save(b);
    }

    @Test
    void weeklyInvoice_deltaUnpaid_excludes_paid_bookings() throws Exception {
        LocalDate monday = LocalDate.of(2025, 2, 3);
        // Two daycare days this week, both quoted at 45.00
        daycare(monday, 1, false);            // unpaid
        daycare(monday.plusDays(1), 1, true); // paid

        // Expect: amount = 90.00 (sum), deltaUnpaid = 45.00 (only the unpaid one)
        mvc.perform(get("/admin/invoices/weekly").param("start", monday.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].amount").value(90.0))
                .andExpect(jsonPath("$[0].deltaUnpaid").value(45.0));
    }

    @Test
    void adminBookings_filters_by_exact_date() throws Exception {
        LocalDate day = LocalDate.of(2025, 2, 3);
        daycare(day, 1, false);
        daycare(day.plusDays(1), 1, false); // next day should NOT appear

        mvc.perform(get("/admin/bookings").param("date", day.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].serviceType").value(Matchers.containsStringIgnoringCase("daycare")))
                .andExpect(jsonPath("$[0].dogCount").value(1));
    }
}
