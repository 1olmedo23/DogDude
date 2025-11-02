package com.dogdaycare.web;

import com.dogdaycare.controller.BookingController;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.FileRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.BookingLimitService;
import com.dogdaycare.service.BundleService;
import com.dogdaycare.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BookingController.class)
@AutoConfigureMockMvc(addFilters = true)
class BookingControllerWebTests {

    @Autowired MockMvc mvc;

    // --- MVC dependencies mocked here ---
    @MockBean UserRepository userRepository;
    @MockBean BookingRepository bookingRepository;
    @MockBean FileRepository fileRepository;
    @MockBean PricingService pricingService;
    @MockBean BundleService bundleService;
    @MockBean BookingLimitService bookingLimitService;
    @MockBean com.dogdaycare.service.CancelPolicyService cancelPolicyService;

    // Spring Security will try to look this up; mock it so @WithMockUser works
    @MockBean UserDetailsService userDetailsService;

    // Provide a fixed clock so “today” is stable in tests
    @MockBean Clock clock;

    private User customer;

    private final ZoneId zone = ZoneId.of("America/Los_Angeles");
    private final LocalDate fixedDate = LocalDate.of(2025, 10, 31); // Fri
    private final Instant fixedInstant = fixedDate.atStartOfDay(zone).toInstant();

    @BeforeEach
    void setup() {
        // Clock -> fixed “now”
        when(clock.getZone()).thenReturn(zone);
        when(clock.instant()).thenReturn(fixedInstant);

        // App user entity your controller expects
        customer = new User();
        customer.setId(123L);
        customer.setUsername("customer@test.local");

        when(userRepository.findByUsername("customer@test.local")).thenReturn(Optional.of(customer));

        // Default: no prior bookings, no files
        when(bookingRepository.findByCustomer(customer)).thenReturn(List.of());
        when(fileRepository.findByUserIdOrderByCreatedAtDesc(123L)).thenReturn(List.of());

        // Default pricing stubs (override per-test as needed)
        when(bundleService.hasWeekPaid(any(User.class), any(LocalDate.class))).thenReturn(false);
        when(pricingService.weekStartMonday(any(LocalDate.class)))
                .thenAnswer(inv -> {
                    LocalDate d = inv.getArgument(0);
                    // Monday of that week
                    return d.minusDays((d.getDayOfWeek().getValue() + 6) % 7);
                });
        when(pricingService.previewDaycarePrice(any(User.class), any(LocalDate.class), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new BigDecimal("50.00"));
        when(pricingService.priceFor(any(Booking.class))).thenReturn(new BigDecimal("65.00"));
        when(bookingLimitService.canCustomerBook(any(LocalDate.class), anyString())).thenReturn(true);

        // Security: return a Spring Security user so authentication succeeds
        when(userDetailsService.loadUserByUsername("customer@test.local"))
                .thenReturn(org.springframework.security.core.userdetails.User
                        .withUsername("customer@test.local")
                        .password("{noop}pw")
                        .roles("CUSTOMER")
                        .build());
    }

    @Test
    void getBooking_rendersTwoWeekGrid_pastDaysDisabled() throws Exception {
        mvc.perform(get("/booking")
                        .with(user("customer@test.local").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(view().name("booking"))
                // page title present
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Book a Service")))
                // banner for Week 1/2 should render
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Plan your week")))
                // “Boarding” button text should exist (verifies grid present)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Boarding")));
    }

    @Test
    void quote_prepayEligibility_respects24hRule() throws Exception {
        // 12:30 same-day -> NOT eligible (hours < 24)
        mvc.perform(get("/booking/quote")
                        .param("serviceType", "Daycare (6 AM - 3 PM)")
                        .param("date", "2025-10-31")
                        .param("time", "12:30")
                        .with(user("customer@test.local").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advanceEligible").value(false));

        // Two days out -> eligible
        mvc.perform(get("/booking/quote")
                        .param("serviceType", "Daycare (6 AM - 3 PM)")
                        .param("date", "2025-11-02")
                        .param("time", "08:00")
                        .with(user("customer@test.local").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advanceEligible").value(true));
    }

    @Test
    void postBooking_rejectsPastDate() throws Exception {
        // yesterday relative to fixed clock
        String past = "2025-10-30";

        mvc.perform(post("/booking")
                        .param("serviceType", "Daycare (6 AM - 3 PM)")
                        .param("date", past)
                        .param("time", "06:00")
                        .param("dogCount", "1")
                        .with(user("customer@test.local").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking"));
    }

    @Test
    void postBooking_rejectsWhenCapacityFull() throws Exception {
        when(bookingLimitService.canCustomerBook(any(LocalDate.class), anyString())).thenReturn(false);

        mvc.perform(post("/booking")
                        .param("serviceType", "Daycare (6 AM - 3 PM)")
                        .param("date", "2025-11-01")
                        .param("time", "08:00")
                        .param("dogCount", "1")
                        .with(user("customer@test.local").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking"));
    }
}
