package com.dogdaycare.service;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BundleServiceTest {

    private BookingRepository bookingRepository;
    private PricingService pricingService;
    private BundleService bundleService;
    private User customer;

    private final LocalDate monday =
            LocalDate.of(2026, 11, 16);

    private final LocalDate sunday =
            LocalDate.of(2026, 11, 22);

    @BeforeEach
    void setup() {
        bookingRepository = mock(BookingRepository.class);
        pricingService = mock(PricingService.class);

        bundleService = new BundleService(
                bookingRepository,
                pricingService
        );

        customer = new User();
        customer.setId(100L);

        when(pricingService.weekStartMonday(any(LocalDate.class)))
                .thenReturn(monday);

        when(pricingService.weekEndSunday(any(LocalDate.class)))
                .thenReturn(sunday);

        when(pricingService.quoteDaycareAtTier(
                any(Booking.class),
                eq(true)
        )).thenReturn(new BigDecimal("45.00"));
    }

    @Test
    void lockWeek_twoDogsTwoDays_storesFullBookingTotals() {
        Booking tuesday = prepaidBooking(
                LocalDate.of(2026, 11, 17), 2
        );

        Booking thursday = prepaidBooking(
                LocalDate.of(2026, 11, 19), 2
        );

        when(bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        customer,
                        "daycare",
                        monday,
                        sunday,
                        "CANCELED"
                ))
                .thenReturn(List.of(tuesday, thursday));

        bundleService.lockAndStampWeekForPayment(
                customer, monday
        );

        // Four qualifying dog-days -> $45 per dog.
        // Each booking has two dogs -> $90 total.
        assertEquals(
                0,
                new BigDecimal("90.00")
                        .compareTo(tuesday.getQuotedRateAtLock())
        );

        assertEquals(
                0,
                new BigDecimal("90.00")
                        .compareTo(thursday.getQuotedRateAtLock())
        );

        assertTrue(tuesday.isInPrepayBundle());
        assertTrue(thursday.isInPrepayBundle());

        assertNotNull(tuesday.getBundleLockedAt());
        assertNotNull(thursday.getBundleLockedAt());

        // Locking prices must not mark bookings paid.
        assertFalse(tuesday.isPaid());
        assertFalse(thursday.isPaid());

        verify(bookingRepository).saveAll(
                List.of(tuesday, thursday)
        );

        verify(pricingService, times(2))
                .quoteDaycareAtTier(any(Booking.class), eq(true));
    }

    @Test
    void lockWeek_preservesPreviouslyPaidBooking() {
        Booking paid = prepaidBooking(
                LocalDate.of(2026, 11, 17), 2
        );

        paid.setPaid(true);
        paid.setQuotedRateAtLock(
                new BigDecimal("100.00")
        );

        Booking unpaid = prepaidBooking(
                LocalDate.of(2026, 11, 19), 2
        );

        when(bookingRepository
                .findByCustomerAndServiceTypeContainingIgnoreCaseAndDateBetweenAndStatusNotIgnoreCase(
                        customer,
                        "daycare",
                        monday,
                        sunday,
                        "CANCELED"
                ))
                .thenReturn(List.of(paid, unpaid));

        bundleService.lockAndStampWeekForPayment(
                customer, monday
        );

        // Existing payment remains unchanged.
        assertTrue(paid.isPaid());

        assertEquals(
                0,
                new BigDecimal("100.00")
                        .compareTo(paid.getQuotedRateAtLock())
        );

        // Only the unpaid booking is locked.
        assertEquals(
                0,
                new BigDecimal("90.00")
                        .compareTo(unpaid.getQuotedRateAtLock())
        );

        assertFalse(unpaid.isPaid());

        verify(bookingRepository).saveAll(
                List.of(unpaid)
        );

        verify(pricingService, never())
                .quoteDaycareAtTier(eq(paid), anyBoolean());
    }

    private Booking prepaidBooking(LocalDate date, int dogs) {
        Booking booking = new Booking();

        booking.setCustomer(customer);
        booking.setDate(date);
        booking.setServiceType(
                "Daycare (6 AM - 8 PM)"
        );
        booking.setStatus("APPROVED");
        booking.setDogCount(dogs);
        booking.setAdvanceEligible(true);
        booking.setWantsAdvancePay(true);
        booking.setPaid(false);

        return booking;
    }
}