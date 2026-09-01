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
import com.dogdaycare.service.SetForgetService;

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
    @MockBean private SetForgetService setForgetService;

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
        when(setForgetService.getActivePlan(any())).thenReturn(null);
        when(setForgetService.generateBookingsForActivePlan(any())).thenReturn(0);

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

    @Test
    void postBooking_validDaycareBooking_savesBooking() throws Exception {

        LocalDate bookingDate = LocalDate.of(2025, 11, 2);

        // No existing booking for this customer on the selected date
        when(bookingRepository.findByCustomerAndDate(customer, bookingDate))
                .thenReturn(List.of());

        // Capacity is available
        when(bookingLimitService.canCustomerBook(
                bookingDate,
                "Daycare (6 AM - 8 PM)"
        )).thenReturn(true);

        // Expected daycare price per dog
        when(pricingService.previewDaycarePrice(
                eq(customer),
                eq(bookingDate),
                eq("Daycare (6 AM - 8 PM)"),
                anyBoolean(),
                anyBoolean()
        )).thenReturn(new BigDecimal("50.00"));

        mvc.perform(post("/booking")
                        .param("serviceType", "Daycare (6 AM - 8 PM)")
                        .param("date", "2025-11-02")
                        .param("time", "06:30")
                        .param("dogCount", "1")
                        .param("wantsAdvancePay", "false")
                        .with(user("customer@test.local").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking"))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Booking submitted successfully! Total: $50.00"
                ));

        verify(bookingRepository).save(argThat(booking ->
                booking.getCustomer() == customer
                        && "Daycare (6 AM - 8 PM)".equals(booking.getServiceType())
                        && bookingDate.equals(booking.getDate())
                        && LocalTime.of(6, 30).equals(booking.getTime())
                        && "APPROVED".equals(booking.getStatus())
                        && Integer.valueOf(1).equals(booking.getDogCount())
                        && new BigDecimal("50.00").compareTo(booking.getQuotedRateAtLock()) == 0
                        && !booking.isWantsAdvancePay()
        ));
    }

    @Test
    void postBooking_rejectsDuplicateSameDayBooking() throws Exception {

        LocalDate bookingDate = LocalDate.of(2025, 11, 2);

        Booking existingBooking = new Booking();
        existingBooking.setCustomer(customer);
        existingBooking.setServiceType("Daycare (6 AM - 3 PM)");
        existingBooking.setDate(bookingDate);
        existingBooking.setTime(LocalTime.of(6, 0));
        existingBooking.setStatus("APPROVED");

        when(bookingRepository.findByCustomerAndDate(customer, bookingDate))
                .thenReturn(List.of(existingBooking));

        mvc.perform(post("/booking")
                        .param("serviceType", "Daycare (6 AM - 8 PM)")
                        .param("date", "2025-11-02")
                        .param("time", "06:30")
                        .param("dogCount", "1")
                        .with(user("customer@test.local").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking"))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "You have already booked a service for this day."
                ));

        verify(bookingRepository, never()).save(any(Booking.class));
        verify(bookingLimitService, never())
                .canCustomerBook(any(LocalDate.class), anyString());
    }


    @Test
    void postBooking_multipleDogs_savesCorrectTotal() throws Exception {

        LocalDate bookingDate = LocalDate.of(2025, 11, 2);

        when(bookingRepository.findByCustomerAndDate(customer, bookingDate))
                .thenReturn(List.of());

        when(bookingLimitService.canCustomerBook(
                bookingDate,
                "Daycare (6 AM - 8 PM)"
        )).thenReturn(true);

        when(pricingService.previewDaycarePrice(
                eq(customer),
                eq(bookingDate),
                eq("Daycare (6 AM - 8 PM)"),
                anyBoolean(),
                anyBoolean()
        )).thenReturn(new BigDecimal("50.00"));

        mvc.perform(post("/booking")
                        .param("serviceType", "Daycare (6 AM - 8 PM)")
                        .param("date", "2025-11-02")
                        .param("time", "06:30")
                        .param("dogCount", "3")
                        .param("wantsAdvancePay", "false")
                        .with(user("customer@test.local").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking"))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Booking submitted successfully! Total: $150.00"
                ));

        verify(bookingRepository).save(argThat(booking ->
                booking.getCustomer() == customer
                        && "Daycare (6 AM - 8 PM)".equals(booking.getServiceType())
                        && bookingDate.equals(booking.getDate())
                        && LocalTime.of(6, 30).equals(booking.getTime())
                        && "APPROVED".equals(booking.getStatus())
                        && Integer.valueOf(3).equals(booking.getDogCount())
                        && new BigDecimal("150.00")
                        .compareTo(booking.getQuotedRateAtLock()) == 0
        ));
    }


    @Test
    void cancelBooking_whenPolicyAllows_cancelsBooking() throws Exception {

        Booking booking = new Booking();
        booking.setId(77L);
        booking.setCustomer(customer);
        booking.setServiceType("Daycare (6 AM - 8 PM)");
        booking.setDate(LocalDate.of(2025, 11, 5));
        booking.setTime(LocalTime.of(6, 30));
        booking.setStatus("APPROVED");

        when(bookingRepository.findById(77L))
                .thenReturn(Optional.of(booking));

        when(cancelPolicyService.canCustomerCancel(booking, clock))
                .thenReturn(true);

        mvc.perform(post("/booking/cancel/77")
                        .with(user("customer@test.local").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking"))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Your booking has been canceled."
                ));

        verify(bookingRepository).save(booking);

        verify(setForgetService)
                .addExceptionForBookingIfNeeded(booking, "CUSTOMER_CANCEL");

        org.junit.jupiter.api.Assertions.assertEquals(
                "CANCELED",
                booking.getStatus()
        );
    }


    @Test
    void cancelBooking_whenPolicyBlocks_doesNotCancelBooking() throws Exception {

        Booking booking = new Booking();
        booking.setId(88L);
        booking.setCustomer(customer);
        booking.setServiceType("Boarding");
        booking.setDate(LocalDate.of(2025, 11, 1));
        booking.setTime(LocalTime.of(6, 30));
        booking.setStatus("APPROVED");

        when(bookingRepository.findById(88L))
                .thenReturn(Optional.of(booking));

        when(cancelPolicyService.canCustomerCancel(booking, clock))
                .thenReturn(false);

        when(cancelPolicyService.policyMessage(booking))
                .thenReturn("This booking can no longer be canceled.");

        mvc.perform(post("/booking/cancel/88")
                        .with(user("customer@test.local").roles("CUSTOMER"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking"))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "This booking can no longer be canceled."
                ));

        verify(bookingRepository, never()).save(any(Booking.class));

        verify(setForgetService, never())
                .addExceptionForBookingIfNeeded(any(Booking.class), anyString());

        org.junit.jupiter.api.Assertions.assertEquals(
                "APPROVED",
                booking.getStatus()
        );
    }
}
