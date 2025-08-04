package com.dogdaycare.controller;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Controller
@RequestMapping("/booking")
public class BookingController {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    private void prepareBookingPage(User customer, Model model, String successMessage) {
        model.addAttribute("bookings", bookingRepository.findByCustomer(customer));
        model.addAttribute("services", List.of(
                "Daycare (6 AM - 3 PM)",
                "Daycare (6 AM - 8 PM)",
                "Boarding"
        ));
        model.addAttribute("activePage", "booking");
        if (successMessage != null) {
            model.addAttribute("successMessage", successMessage);
        }
    }

    public BookingController(BookingRepository bookingRepository, UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String bookingPage(Authentication authentication, Model model) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();
        prepareBookingPage(customer, model, null);
        return "booking";
    }

    @PostMapping
    public String createBooking(
            Authentication authentication,
            @RequestParam String serviceType,
            @RequestParam String date,
            @RequestParam String time,
            RedirectAttributes redirectAttributes
    ) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();
        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setServiceType(serviceType);
        booking.setDate(LocalDate.parse(date));
        booking.setTime(LocalTime.parse(time));
        booking.setStatus("APPROVED"); // Default approved
        bookingRepository.save(booking);

        redirectAttributes.addFlashAttribute("successMessage", "Booking submitted successfully!");
        return "redirect:/booking";
    }

    @PostMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id, Authentication authentication, RedirectAttributes redirectAttributes) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking != null && booking.getCustomer().getUsername().equals(authentication.getName())) {
            booking.setStatus("CANCELED");
            bookingRepository.save(booking);
            redirectAttributes.addFlashAttribute("successMessage", "Your booking has been canceled.");
        }
        return "redirect:/booking";
    }
}