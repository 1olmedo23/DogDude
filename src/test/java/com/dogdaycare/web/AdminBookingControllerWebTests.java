package com.dogdaycare.web;

import com.dogdaycare.controller.AdminBookingController;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.Invoice;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EmergencyAllocationRepository;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.InvoiceRepository;
import com.dogdaycare.service.BookingLimitService;
import com.dogdaycare.service.PricingService;
import com.dogdaycare.service.SetForgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminBookingController.class)
@AutoConfigureMockMvc(addFilters = true)
class AdminBookingControllerWebTests {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private BookingRepository bookingRepository;

    @MockBean
    private EvaluationRepository evaluationRepository;

    @MockBean
    private EmergencyAllocationRepository emergencyAllocationRepository;

    @MockBean
    private InvoiceRepository invoiceRepository;

    @MockBean
    private PricingService pricingService;

    @MockBean
    private BookingLimitService bookingLimitService;

    @MockBean
    private SetForgetService setForgetService;

    @MockBean
    private UserDetailsService userDetailsService;

    private User customer;
    private Booking booking;

    private final LocalDate selectedDate =
            LocalDate.of(2026, 9, 15);

    @BeforeEach
    void setup() {

        customer = new User();
        customer.setId(123L);
        customer.setUsername("customer@test.local");

        booking = new Booking();
        booking.setId(77L);
        booking.setCustomer(customer);
        booking.setServiceType("Daycare (6 AM - 8 PM)");
        booking.setDate(selectedDate);
        booking.setTime(LocalTime.of(6, 30));
        booking.setStatus("APPROVED");
        booking.setDogCount(1);
        booking.setQuotedRateAtLock(new BigDecimal("50.00"));

        when(userDetailsService.loadUserByUsername("admin@test.local"))
                .thenReturn(org.springframework.security.core.userdetails.User
                        .withUsername("admin@test.local")
                        .password("{noop}pw")
                        .roles("ADMIN")
                        .build());
    }

    @Test
    void adjustBooking_validAdjustment_savesAndPreservesSelectedDate()
            throws Exception {

        when(bookingRepository.findById(77L))
                .thenReturn(Optional.of(booking));

        mvc.perform(post("/admin/bookings/adjust/77")
                        .param("amount", "25")
                        .param("reason", " Extra care required ")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin?date=2026-09-15#bookings"
                ))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Booking adjustment updated."
                ));

        verify(bookingRepository).save(booking);

        assertEquals(
                0,
                new BigDecimal("25.00")
                        .compareTo(booking.getManualAdjustmentAmount())
        );

        assertEquals(
                "Extra care required",
                booking.getManualAdjustmentReason()
        );

        assertNotNull(
                booking.getManualAdjustmentUpdatedAt()
        );
    }

    @Test
    void adjustBooking_nonZeroWithoutReason_isRejected()
            throws Exception {

        mvc.perform(post("/admin/bookings/adjust/77")
                        .param("amount", "25")
                        .param("reason", "   ")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin?date=2026-09-15#bookings"
                ))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "Please enter a short reason for the adjustment."
                ));

        verify(bookingRepository, never())
                .findById(any());

        verify(bookingRepository, never())
                .save(any(Booking.class));
    }

    @Test
    void adjustBooking_zero_clearsAdjustment()
            throws Exception {

        booking.setManualAdjustmentAmount(
                new BigDecimal("25.00")
        );
        booking.setManualAdjustmentReason(
                "Previous adjustment"
        );
        booking.setManualAdjustmentUpdatedAt(
                LocalDateTime.now().minusDays(1)
        );

        when(bookingRepository.findById(77L))
                .thenReturn(Optional.of(booking));

        mvc.perform(post("/admin/bookings/adjust/77")
                        .param("amount", "0")
                        .param("reason", "")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin?date=2026-09-15#bookings"
                ))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Booking adjustment cleared."
                ));

        verify(bookingRepository).save(booking);

        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(
                        booking.getManualAdjustmentAmount()
                )
        );

        assertNull(
                booking.getManualAdjustmentReason()
        );

        assertNotNull(
                booking.getManualAdjustmentUpdatedAt()
        );
    }

    @Test
    void cancelBooking_cancelsAndPreservesSelectedDate()
            throws Exception {

        when(bookingRepository.findById(77L))
                .thenReturn(Optional.of(booking));

        mvc.perform(post("/admin/bookings/cancel/77")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin?date=2026-09-15#bookings"
                ))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Booking canceled."
                ));

        assertEquals(
                "CANCELED",
                booking.getStatus()
        );

        verify(bookingRepository).save(booking);

        verify(setForgetService)
                .addExceptionForBookingIfNeeded(
                        booking,
                        "ADMIN_CANCEL"
                );

        verify(emergencyAllocationRepository)
                .deleteByBookingId(77L);
    }

    @Test
    void markPaid_marksBookingPaidAndPreservesSelectedDate()
            throws Exception {

        booking.setPaid(false);
        booking.setPaidAt(null);

        when(bookingRepository.findById(77L))
                .thenReturn(Optional.of(booking));

        LocalDate weekStart =
                LocalDate.of(2026, 9, 14);

        LocalDate weekEnd =
                LocalDate.of(2026, 9, 20);

        when(bookingRepository.findByDateBetween(
                weekStart,
                weekEnd
        )).thenReturn(List.of(booking));

        when(invoiceRepository
                .findByCustomerEmailAndWeekStart(
                        "customer@test.local",
                        weekStart
                ))
                .thenReturn(Optional.empty());

        when(pricingService.priceFor(booking))
                .thenReturn(new BigDecimal("50.00"));

        mvc.perform(post("/admin/bookings/mark-paid/77")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin?date=2026-09-15#bookings"
                ))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Booking marked paid."
                ));

        assertTrue(
                booking.isPaid()
        );

        assertNotNull(
                booking.getPaidAt()
        );

        verify(bookingRepository).save(booking);

        /*
         * Because this is the only active booking in the mocked
         * week and it is now paid, the controller should also
         * create/update the weekly invoice.
         */
        verify(invoiceRepository)
                .save(any(Invoice.class));
    }

    @Test
    void revertPaid_revertsPaymentAndPreservesSelectedDate()
            throws Exception {

        booking.setPaid(true);
        booking.setPaidAt(
                LocalDateTime.now().minusHours(1)
        );

        when(bookingRepository.findById(77L))
                .thenReturn(Optional.of(booking));

        mvc.perform(post("/admin/bookings/revert-paid/77")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin?date=2026-09-15#bookings"
                ))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Booking payment reverted."
                ));

        assertFalse(
                booking.isPaid()
        );

        assertNull(
                booking.getPaidAt()
        );

        verify(bookingRepository).save(booking);
    }

    @Test
    void adjustBooking_invalidIncrement_isRejected()
            throws Exception {

        mvc.perform(post("/admin/bookings/adjust/77")
                        .param("amount", "23")
                        .param("reason", "Invalid adjustment test")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin?date=2026-09-15#bookings"
                ))
                .andExpect(flash().attribute(
                        "errorMessage",
                        "Adjustment must be between -100 and 100 in $5 increments."
                ));

        verify(bookingRepository, never())
                .findById(any());

        verify(bookingRepository, never())
                .save(any(Booking.class));
    }

    @Test
    void getBookingsByDate_sortsByTimeThenId() throws Exception {

        Booking laterBooking = new Booking();
        laterBooking.setId(30L);
        laterBooking.setCustomer(customer);
        laterBooking.setServiceType("Daycare (6 AM - 8 PM)");
        laterBooking.setDate(selectedDate);
        laterBooking.setTime(LocalTime.of(8, 0));
        laterBooking.setStatus("APPROVED");
        laterBooking.setDogCount(1);
        laterBooking.setQuotedRateAtLock(new BigDecimal("50.00"));

        Booking sameTimeHigherId = new Booking();
        sameTimeHigherId.setId(20L);
        sameTimeHigherId.setCustomer(customer);
        sameTimeHigherId.setServiceType("Daycare (6 AM - 8 PM)");
        sameTimeHigherId.setDate(selectedDate);
        sameTimeHigherId.setTime(LocalTime.of(7, 0));
        sameTimeHigherId.setStatus("APPROVED");
        sameTimeHigherId.setDogCount(1);
        sameTimeHigherId.setQuotedRateAtLock(new BigDecimal("50.00"));

        Booking sameTimeLowerId = new Booking();
        sameTimeLowerId.setId(10L);
        sameTimeLowerId.setCustomer(customer);
        sameTimeLowerId.setServiceType("Daycare (6 AM - 8 PM)");
        sameTimeLowerId.setDate(selectedDate);
        sameTimeLowerId.setTime(LocalTime.of(7, 0));
        sameTimeLowerId.setStatus("APPROVED");
        sameTimeLowerId.setDogCount(1);
        sameTimeLowerId.setQuotedRateAtLock(new BigDecimal("50.00"));

        /*
         * Deliberately return the bookings in the wrong order.
         * The controller must sort them itself.
         */
        when(bookingRepository.findByDate(selectedDate))
                .thenReturn(List.of(
                        laterBooking,
                        sameTimeHigherId,
                        sameTimeLowerId
                ));

        when(evaluationRepository.findTopByEmailOrderByCreatedAtDesc(
                "customer@test.local"
        )).thenReturn(Optional.empty());

        mvc.perform(get("/admin/bookings")
                        .param("date", "2026-09-15")
                        .with(user("admin@test.local").roles("ADMIN")))
                .andExpect(status().isOk())

                // 7:00, lower ID first
                .andExpect(jsonPath("$[0].id").value(10))

                // 7:00, higher ID second
                .andExpect(jsonPath("$[1].id").value(20))

                // 8:00 booking last
                .andExpect(jsonPath("$[2].id").value(30));
    }
}