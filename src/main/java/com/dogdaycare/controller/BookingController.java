package com.dogdaycare.controller;

import com.dogdaycare.service.PricingService;
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

import com.dogdaycare.service.PricingService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneId;

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
    private final PricingService pricingService;

    public BookingController(BookingRepository bookingRepository,
                             UserRepository userRepository,
                             BookingLimitService bookingLimitService,
                             CancelPolicyService cancelPolicyService,
                             FileRepository fileRepository,
                             PricingService pricingService) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.bookingLimitService = bookingLimitService;
        this.cancelPolicyService = cancelPolicyService;
        this.fileRepository = fileRepository;
        this.pricingService = pricingService;
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
                                @RequestParam(name = "wantsAdvancePay", required = false, defaultValue = "false") boolean wantsAdvancePay,
                                RedirectAttributes redirectAttributes) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        LocalDate localDate = LocalDate.parse(date);
        LocalTime localTime = LocalTime.parse(time);

        // Capacity check
        boolean canBook = bookingLimitService.canCustomerBook(localDate, serviceType);
        if (!canBook) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "We’re full for this day. Please try a different date. " +
                            "If this is an emergency, please contact the business at (XXX) XXX-XXXX."
            );
            return "redirect:/booking";
        }

        // Compute advance eligibility (24h, daycare only)
        final boolean isDaycareFlag =
                serviceType != null && serviceType.toLowerCase().contains("daycare");

        boolean advanceEligible = false;
        if (isDaycareFlag) {
            var now = java.time.ZonedDateTime.now();
            var bookingZdt = java.time.ZonedDateTime.of(localDate, localTime, now.getZone());
            long hours = java.time.Duration.between(now, bookingZdt).toHours();
            advanceEligible = hours >= 24;
        }

        // Build + persist
        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setServiceType(serviceType);
        booking.setDate(localDate);
        booking.setTime(localTime);
        booking.setStatus("APPROVED");

// audit/flags
        booking.setCreatedAt(LocalDateTime.now());
        booking.setAdvanceEligible(advanceEligible);
        booking.setWantsAdvancePay(advanceEligible && wantsAdvancePay);
        booking.setInPrepayBundle(booking.isWantsAdvancePay()); // purely informational

        // lock a quote (base rates for now; we’ll adjust at bundle/weekly lock later)
        booking.setQuotedRateAtLock(pricingService.priceFor(booking));

        bookingRepository.save(booking);

        // Friendly success
        String msg = "Booking submitted successfully!";
        if (booking.isWantsAdvancePay()) {
            msg += " We’ll contact you to process the advance payment at the discounted rate.";
        }
        redirectAttributes.addFlashAttribute("successMessage", msg);
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
