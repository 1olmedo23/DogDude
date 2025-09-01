package com.dogdaycare.controller;

import com.dogdaycare.dto.BookingRowDto;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EvaluationRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/bookings")
public class AdminBookingController {

    private final BookingRepository bookingRepository;
    private final EvaluationRepository evaluationRepository;

    public AdminBookingController(BookingRepository bookingRepository,
                                  EvaluationRepository evaluationRepository) {
        this.bookingRepository = bookingRepository;
        this.evaluationRepository = evaluationRepository;
    }

    @GetMapping
    @ResponseBody
    public List<BookingRowDto> getBookingsByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<Booking> bookings = bookingRepository.findByDate(date);

        return bookings.stream().map(b -> {
            // Email is the username for customers in your system
            String email = (b.getCustomer() != null) ? b.getCustomer().getUsername() : "N/A";

            // Pull most recent evaluation for display name/dog
            Optional<EvaluationRequest> evalOpt =
                    (email == null || "N/A".equals(email))
                            ? Optional.empty()
                            : evaluationRepository.findTopByEmailOrderByCreatedAtDesc(email);

            String customerName = evalOpt.map(EvaluationRequest::getClientName).orElse(email != null ? email : "N/A");
            String dogName = evalOpt.map(EvaluationRequest::getDogName).orElse("N/A");

            // Time → string for JS
            String timeStr = (b.getTime() != null) ? b.getTime().toString() : "";

            return new BookingRowDto(
                    b.getId(),
                    customerName,
                    email,
                    dogName,
                    b.getServiceType(),
                    timeStr,
                    b.getStatus()
            );
        }).collect(Collectors.toList());
    }

    @PostMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        bookingRepository.findById(id).ifPresent(booking -> {
            booking.setStatus("CANCELED");
            bookingRepository.save(booking);
            redirectAttributes.addFlashAttribute("successMessage", "Booking canceled successfully.");
        });
        return "redirect:/admin";
    }
}
