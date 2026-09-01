package com.dogdaycare.web;

import com.dogdaycare.controller.AdminEmergencyBookingController;
import com.dogdaycare.dto.EmergencyCounts;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EmergencyAllocationRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.BookingLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminEmergencyBookingController.class)
@AutoConfigureMockMvc(addFilters = true)
class AdminEmergencyBookingControllerWebTests {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private BookingRepository bookingRepository;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private EmergencyAllocationRepository emergencyAllocationRepository;

    @MockBean
    private BookingLimitService bookingLimitService;

    @MockBean
    private UserDetailsService userDetailsService;

    private User customer;

    private final LocalDate testDate = LocalDate.of(2026, 9, 15);

    @BeforeEach
    void setup() {

        customer = new User();
        customer.setId(123L);
        customer.setUsername("customer@test.local");

        when(userRepository.findByUsername("customer@test.local"))
                .thenReturn(Optional.of(customer));

        when(bookingRepository.findByCustomerAndDate(customer, testDate))
                .thenReturn(List.of());

        /*
         * The controller needs the saved booking ID when it creates an
         * EmergencyAllocation, so make the mocked repository behave
         * like a successful database save.
         */
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> {
                    Booking booking = invocation.getArgument(0);
                    booking.setId(500L);
                    return booking;
                });

        when(userDetailsService.loadUserByUsername("admin@test.local"))
                .thenReturn(org.springframework.security.core.userdetails.User
                        .withUsername("admin@test.local")
                        .password("{noop}pw")
                        .roles("ADMIN")
                        .build());
    }

    @Test
    void below60_withoutForceBooking_requiresConfirmation() throws Exception {

        EmergencyCounts counts = counts(59, 0, 10);

        when(bookingLimitService.snapshot(testDate))
                .thenReturn(counts);

        mvc.perform(post("/admin/emergency")
                        .param("customerEmail", "customer@test.local")
                        .param("serviceType", "Boarding")
                        .param("date", "2026-09-15")
                        .param("time", "06:30")
                        .param("forceBooking", "false")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin_emergency"))
                .andExpect(model().attribute("confirmationRequired", true))
                .andExpect(model().attribute(
                        "confirmationMessage",
                        "Bookings aren’t full for this day. Book anyway?"
                ));

        verify(bookingRepository, never()).save(any(Booking.class));

        verify(emergencyAllocationRepository, never())
                .save(any());
    }

    @Test
    void below60_withForceBooking_createsRegularAdminBooking() throws Exception {

        EmergencyCounts before = counts(59, 0, 10);
        EmergencyCounts after = counts(60, 0, 10);

        when(bookingLimitService.snapshot(testDate))
                .thenReturn(before, after);

        mvc.perform(post("/admin/emergency")
                        .param("customerEmail", "customer@test.local")
                        .param("serviceType", "Boarding")
                        .param("date", "2026-09-15")
                        .param("time", "06:30")
                        .param("forceBooking", "true")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin_emergency"))
                .andExpect(model().attribute(
                        "message",
                        "Admin booking created for customer@test.local (Boarding)."
                ))
                .andExpect(model().attribute("confirmationRequired", false));

        verify(bookingRepository).save(argThat(booking ->
                booking.getCustomer() == customer
                        && "Boarding".equals(booking.getServiceType())
                        && testDate.equals(booking.getDate())
                        && "APPROVED".equals(booking.getStatus())
        ));

        /*
         * A booking created while total is below 60 must NOT use
         * an emergency allocation.
         */
        verify(emergencyAllocationRepository, never())
                .save(any());
    }

    @Test
    void at60_createsEmergencyBookingAndAllocation() throws Exception {

        EmergencyCounts before = counts(60, 0, 10);
        EmergencyCounts after = counts(61, 1, 9);

        when(bookingLimitService.snapshot(testDate))
                .thenReturn(before, after);

        mvc.perform(post("/admin/emergency")
                        .param("customerEmail", "customer@test.local")
                        .param("serviceType", "Boarding")
                        .param("date", "2026-09-15")
                        .param("time", "06:30")
                        .param("forceBooking", "false")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin_emergency"))
                .andExpect(model().attribute(
                        "message",
                        "Emergency booking created for customer@test.local (Boarding)."
                ))
                .andExpect(model().attribute("confirmationRequired", false));

        verify(bookingRepository).save(argThat(booking ->
                booking.getCustomer() == customer
                        && "Boarding".equals(booking.getServiceType())
                        && testDate.equals(booking.getDate())
                        && "APPROVED".equals(booking.getStatus())
        ));

        verify(emergencyAllocationRepository).save(argThat(allocation ->
                testDate.equals(allocation.getDate())
                        && Long.valueOf(500L).equals(allocation.getBookingId())
        ));
    }

    @Test
    void at70_blocksBookingCompletely() throws Exception {

        EmergencyCounts counts = counts(70, 10, 0);

        when(bookingLimitService.snapshot(testDate))
                .thenReturn(counts);

        mvc.perform(post("/admin/emergency")
                        .param("customerEmail", "customer@test.local")
                        .param("serviceType", "Boarding")
                        .param("date", "2026-09-15")
                        .param("time", "06:30")
                        .param("forceBooking", "true")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin_emergency"))
                .andExpect(model().attribute(
                        "error",
                        "The daily total is full for this date."
                ))
                .andExpect(model().attribute("confirmationRequired", false));

        verify(bookingRepository, never()).save(any(Booking.class));

        verify(emergencyAllocationRepository, never())
                .save(any());

        /*
         * The hard-cap check happens before customer lookup, so the
         * controller should stop immediately.
         */
        verify(userRepository, never())
                .findByUsername(anyString());
    }

    private EmergencyCounts counts(int total,
                                   int emergencyUsed,
                                   int emergencyRemaining) {

        EmergencyCounts counts = mock(EmergencyCounts.class);

        when(counts.getTotal()).thenReturn(total);

        when(counts.totalCap()).thenReturn(70);
        when(counts.daycareCap()).thenReturn(40);
        when(counts.boardingCap()).thenReturn(20);

        when(counts.getEmergencyUsed()).thenReturn(emergencyUsed);
        when(counts.getEmergencyRemaining()).thenReturn(emergencyRemaining);

        return counts;
    }
}