package com.dogdaycare.controller;

import com.dogdaycare.model.Booking;
import com.dogdaycare.repository.BookingRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/admin/bookings")
public class AdminBookingController {

    private final BookingRepository bookingRepository;

    public AdminBookingController(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @GetMapping
    @ResponseBody
    public List<Booking> getBookingsByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return bookingRepository.findByDate(date);
    }

    @PostMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking != null) {
            booking.setStatus("CANCELED");
            bookingRepository.save(booking);
            redirectAttributes.addFlashAttribute("successMessage", "Booking canceled successfully.");
        }
        return "redirect:/admin";
    }
}