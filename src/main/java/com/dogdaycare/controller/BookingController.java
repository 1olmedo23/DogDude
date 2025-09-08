package com.dogdaycare.controller;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.repository.FileRepository;
import com.dogdaycare.service.BookingLimitService;
import com.dogdaycare.service.CancelPolicyService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

@Controller
@RequestMapping("/booking")
public class BookingController {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final BookingLimitService bookingLimitService;
    private final CancelPolicyService cancelPolicyService;
    private final Clock clock = Clock.systemDefaultZone();
    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private final FileRepository fileRepository;

    public BookingController(BookingRepository bookingRepository,
                             UserRepository userRepository,
                             BookingLimitService bookingLimitService,
                             CancelPolicyService cancelPolicyService,
                             FileRepository fileRepository) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.bookingLimitService = bookingLimitService;
        this.cancelPolicyService = cancelPolicyService;
        this.fileRepository = fileRepository;
    }

    private void prepareBookingPage(User customer, Model model, String successMessage, String errorMessage) {
        var all = bookingRepository.findByCustomer(customer);

        Comparator<Booking> sortByDateThenTime =
                Comparator.comparing(Booking::getDate)
                        .thenComparing(Booking::getTime, Comparator.nullsLast(Comparator.naturalOrder()));

        // Existing grouping
        var daycare = all.stream()
                .filter(b -> b.getServiceType() != null && b.getServiceType().toLowerCase().contains("daycare"))
                .sorted(sortByDateThenTime)
                .toList();

        var boarding = all.stream()
                .filter(b -> b.getServiceType() != null && b.getServiceType().toLowerCase().contains("boarding"))
                .sorted(sortByDateThenTime)
                .toList();

        // ✳️ NEW: split daycare into Short vs Long, matching your exact service_type strings
        var daycareShort = daycare.stream()
                .filter(b -> "Daycare (6 AM - 3 PM)".equalsIgnoreCase(safe(b.getServiceType())))
                .toList();

        var daycareLong = daycare.stream()
                .filter(b -> "Daycare (6 AM - 8 PM)".equalsIgnoreCase(safe(b.getServiceType())))
                .toList();

        var files = fileRepository.findByUserIdOrderByCreatedAtDesc(customer.getId());
        model.addAttribute("files", files);

        // Keep originals for compatibility
        model.addAttribute("bookings", all);
        model.addAttribute("bookingsDaycare", daycare);
        model.addAttribute("bookingsBoarding", boarding);

        // ✅ Attributes the view actually renders
        model.addAttribute("bookingsDaycareShort", daycareShort);
        model.addAttribute("bookingsDaycareLong", daycareLong);

        model.addAttribute("services", List.of(
                "Daycare (6 AM - 3 PM)",
                "Daycare (6 AM - 8 PM)",
                "Boarding"
        ));
        model.addAttribute("activePage", "booking");

        if (successMessage != null && !successMessage.isBlank()) {
            model.addAttribute("successMessage", successMessage);
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            model.addAttribute("errorMessage", errorMessage);
        }
    }

    @GetMapping
    public String bookingPage(Authentication authentication, Model model,
                              @ModelAttribute("successMessage") String successMessage,
                              @ModelAttribute("errorMessage") String errorMessage) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();
        prepareBookingPage(customer, model,
                (successMessage != null && !successMessage.isBlank()) ? successMessage : null,
                (errorMessage != null && !errorMessage.isBlank()) ? errorMessage : null);
        return "booking";
    }

    @PostMapping
    public String createBooking(Authentication authentication,
                                @RequestParam String serviceType,
                                @RequestParam String date,
                                @RequestParam String time,
                                RedirectAttributes redirectAttributes) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        LocalDate localDate = LocalDate.parse(date);
        LocalTime localTime = LocalTime.parse(time);

        // Capacity check for customers
        boolean canBook = bookingLimitService.canCustomerBook(localDate, serviceType);
        if (!canBook) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "We’re full for this day. Please try a different date. " +
                            "If this is an emergency, please contact the business at (XXX) XXX-XXXX."
            );
            return "redirect:/booking";
        }

        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setServiceType(serviceType);
        booking.setDate(localDate);
        booking.setTime(localTime);
        booking.setStatus("APPROVED"); // as per your current flow
        bookingRepository.save(booking);

        redirectAttributes.addFlashAttribute("successMessage", "Booking submitted successfully!");
        return "redirect:/booking";
    }

    @PostMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking != null && booking.getCustomer() != null
                && booking.getCustomer().getUsername().equals(authentication.getName())) {

            // Enforce 72-hour rule for Boarding (customers only)
            boolean canCancel = cancelPolicyService.canCustomerCancel(booking, clock);
            if (!canCancel) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        cancelPolicyService.policyMessage(booking)
                );
                return "redirect:/booking";
            }

            booking.setStatus("CANCELED");
            bookingRepository.save(booking);
            redirectAttributes.addFlashAttribute("successMessage", "Your booking has been canceled.");
        }
        return "redirect:/booking";
    }
}
