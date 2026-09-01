package com.dogdaycare.it;

import com.dogdaycare.dto.EmergencyCounts;
import com.dogdaycare.model.Booking;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.service.BookingLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BookingLimitServiceTest {

    @Autowired
    private BookingLimitService bookingLimitService;

    @Autowired
    private BookingRepository bookingRepository;

    private final LocalDate testDate = LocalDate.of(2026, 9, 15);

    @BeforeEach
    void setup() {
        bookingRepository.deleteAll();
    }

    @Test
    void customerDaycareCapacity_allows39_blocks40() {

        // 39 active daycare bookings -> one normal daycare spot remains
        createBookings(
                39,
                "Daycare (6 AM - 3 PM)",
                "APPROVED"
        );

        EmergencyCounts countsAt39 = bookingLimitService.snapshot(testDate);

        assertEquals(39, countsAt39.getDaycare());
        assertEquals(39, countsAt39.getTotal());

        assertTrue(
                bookingLimitService.canCustomerBook(
                        testDate,
                        "Daycare (6 AM - 3 PM)"
                )
        );

        // Add the 40th active daycare booking
        createBooking(
                "Daycare (6 AM - 3 PM)",
                "APPROVED"
        );

        EmergencyCounts countsAt40 = bookingLimitService.snapshot(testDate);

        assertEquals(40, countsAt40.getDaycare());
        assertEquals(40, countsAt40.getTotal());

        assertFalse(
                bookingLimitService.canCustomerBook(
                        testDate,
                        "Daycare (6 AM - 3 PM)"
                )
        );
    }

    @Test
    void customerBoardingCapacity_allows19_blocks20() {

        // 19 active boarding bookings -> one normal boarding spot remains
        createBookings(
                19,
                "Boarding",
                "APPROVED"
        );

        EmergencyCounts countsAt19 = bookingLimitService.snapshot(testDate);

        assertEquals(19, countsAt19.getBoarding());
        assertEquals(19, countsAt19.getTotal());

        assertTrue(
                bookingLimitService.canCustomerBook(
                        testDate,
                        "Boarding"
                )
        );

        // Add the 20th active boarding booking
        createBooking(
                "Boarding",
                "APPROVED"
        );

        EmergencyCounts countsAt20 = bookingLimitService.snapshot(testDate);

        assertEquals(20, countsAt20.getBoarding());
        assertEquals(20, countsAt20.getTotal());

        assertFalse(
                bookingLimitService.canCustomerBook(
                        testDate,
                        "Boarding"
                )
        );
    }

    @Test
    void adminEmergencyCapacity_startsAt60_andStopsAt70() {

        // 40 daycare + 19 boarding = 59 active bookings
        createBookings(
                40,
                "Daycare (6 AM - 3 PM)",
                "APPROVED"
        );

        createBookings(
                19,
                "Boarding",
                "APPROVED"
        );

        EmergencyCounts countsAt59 = bookingLimitService.snapshot(testDate);

        assertEquals(59, countsAt59.getTotal());
        assertEquals(0, countsAt59.getEmergencyUsed());

        // Below 60, an admin booking is still a regular booking.
        assertFalse(
                bookingLimitService.shouldUseEmergency(
                        testDate,
                        "Boarding"
                )
        );

        // Add booking #60.
        createBooking(
                "Boarding",
                "APPROVED"
        );

        EmergencyCounts countsAt60 = bookingLimitService.snapshot(testDate);

        assertEquals(60, countsAt60.getTotal());
        assertEquals(0, countsAt60.getEmergencyUsed());

        // At 60 active bookings, the NEXT admin booking uses emergency capacity.
        assertTrue(
                bookingLimitService.shouldUseEmergency(
                        testDate,
                        "Boarding"
                )
        );

        assertTrue(
                bookingLimitService.canUseEmergency(testDate)
        );

        // Add 9 emergency-overflow bookings: total becomes 69.
        createBookings(
                9,
                "Boarding",
                "APPROVED"
        );

        EmergencyCounts countsAt69 = bookingLimitService.snapshot(testDate);

        assertEquals(69, countsAt69.getTotal());
        assertEquals(9, countsAt69.getEmergencyUsed());
        assertEquals(1, countsAt69.emergencyRemaining());

        assertTrue(
                bookingLimitService.shouldUseEmergency(
                        testDate,
                        "Boarding"
                )
        );

        assertTrue(
                bookingLimitService.canUseEmergency(testDate)
        );

        // Add booking #70.
        createBooking(
                "Boarding",
                "APPROVED"
        );

        EmergencyCounts countsAt70 = bookingLimitService.snapshot(testDate);

        assertEquals(70, countsAt70.getTotal());
        assertEquals(10, countsAt70.getEmergencyUsed());
        assertEquals(0, countsAt70.emergencyRemaining());

        // Hard daily cap reached.
        assertFalse(
                bookingLimitService.shouldUseEmergency(
                        testDate,
                        "Boarding"
                )
        );

        assertFalse(
                bookingLimitService.canUseEmergency(testDate)
        );
    }

    @Test
    void canceledBookings_doNotConsumeCapacity() {

        // 39 active daycare bookings
        createBookings(
                39,
                "Daycare (6 AM - 3 PM)",
                "APPROVED"
        );

        // Add several canceled bookings on the same day.
        createBookings(
                10,
                "Daycare (6 AM - 3 PM)",
                "CANCELED"
        );

        EmergencyCounts counts = bookingLimitService.snapshot(testDate);

        // Canceled bookings must not be included.
        assertEquals(39, counts.getDaycare());
        assertEquals(39, counts.getTotal());
        assertEquals(0, counts.getEmergencyUsed());

        // Customer should still be able to take the 40th active daycare spot.
        assertTrue(
                bookingLimitService.canCustomerBook(
                        testDate,
                        "Daycare (6 AM - 3 PM)"
                )
        );
    }

    private void createBookings(int count,
                                String serviceType,
                                String status) {

        for (int i = 0; i < count; i++) {
            createBooking(serviceType, status);
        }
    }

    private void createBooking(String serviceType,
                               String status) {

        Booking booking = new Booking();

        booking.setServiceType(serviceType);
        booking.setDate(testDate);
        booking.setTime(LocalTime.of(6, 0));
        booking.setStatus(status);
        booking.setDogCount(1);

        bookingRepository.save(booking);
    }
}