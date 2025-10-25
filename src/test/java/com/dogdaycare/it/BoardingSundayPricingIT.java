package com.dogdaycare.it;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.PricingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

@SpringBootTest
@Transactional
public class  BoardingSundayPricingIT {

    @Autowired
    private PricingService pricingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void sundayBoarding_addsPickupUnlessMondayDaycare() {
        // Arrange: pick a known Sunday
        LocalDate sunday = LocalDate.of(2025, 10, 19);
        Assertions.assertEquals(DayOfWeek.SUNDAY, sunday.getDayOfWeek(), "Test date must be a Sunday");

        // Create a customer
        User customer = new User();
        customer.setUsername("alice@example.com");
        customer.setPassword("{noop}pw");
        customer.setRole("CUSTOMER");
        customer.setEnabled(true);
        customer = userRepository.save(customer);

        // Create a Sunday boarding booking
        Booking sundayBoarding = new Booking();
        sundayBoarding.setCustomer(customer);
        sundayBoarding.setDate(sunday);
        sundayBoarding.setServiceType("Boarding");
        sundayBoarding.setStatus("APPROVED");
        sundayBoarding.setDogCount(1);
        sundayBoarding = bookingRepository.save(sundayBoarding);

        // A) No Monday daycare -> add 0.5x ($90 -> $135)
        BigDecimal priceWithoutDaycare = pricingService.priceFor(sundayBoarding);
        Assertions.assertEquals(new BigDecimal("135.00"), priceWithoutDaycare, "Expected $135.00 without Monday daycare");

        // Add Monday daycare (regular daycare, not After Hours)
        LocalDate monday = sunday.plusDays(1);
        Booking mondayDaycare = new Booking();
        mondayDaycare.setCustomer(customer);
        mondayDaycare.setDate(monday);
        mondayDaycare.setServiceType("Daycare (6 AM - 3 PM)");
        mondayDaycare.setStatus("APPROVED");
        mondayDaycare.setDogCount(1);
        bookingRepository.save(mondayDaycare);

        // B) With Monday daycare -> no pickup (stay at $90)
        BigDecimal priceWithDaycare = pricingService.priceFor(sundayBoarding);
        Assertions.assertEquals(new BigDecimal("90.00"), priceWithDaycare, "Expected $90.00 with Monday daycare");
    }
}
