package com.dogdaycare.controller;

import com.dogdaycare.dto.EmergencyCounts;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EmergencyAllocation;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EmergencyAllocationRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.BookingLimitService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;

@Controller
@RequestMapping("/admin/emergency")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
public class AdminEmergencyBookingController {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EmergencyAllocationRepository emergencyRepo;
    private final BookingLimitService limitService;

    private static final String DAYCARE_HALF = "Daycare (6 AM - 3 PM)";
    private static final String DAYCARE_EVENING = "Daycare (6 AM - 8 PM)";
    private static final String DAYCARE_AFTER_HOURS =
            "Daycare After Hours (6 AM - 11 PM)";
    private static final String BOARDING = "Boarding";

    private static final Set<String> ALLOWED_SERVICES = Set.of(
            DAYCARE_HALF,
            DAYCARE_EVENING,
            DAYCARE_AFTER_HOURS,
            BOARDING
    );

    // --------- GET: page ----------
    @GetMapping
    public String page(@RequestParam(value = "date", required = false) String dateStr,
                       Model model) {

        LocalDate date = parseDateOrToday(dateStr);
        EmergencyCounts counts = limitService.snapshot(date);

        model.addAttribute("date", date);
        model.addAttribute("counts", counts);
        model.addAttribute("form", new EmergencyForm(date));
        model.addAttribute("message", null);
        model.addAttribute("error", null);
        model.addAttribute("confirmationRequired", false);

        return "admin_emergency";
    }

    // --------- POST: create admin/emergency booking ----------
    @PostMapping
    public String submit(@ModelAttribute("form") EmergencyForm form,
                         BindingResult binding,
                         Model model) {

        if (binding.hasFieldErrors("date") || form.getDate() == null) {
            LocalDate displayDate = LocalDate.now();
            EmergencyCounts counts = limitService.snapshot(displayDate);

            return withError(
                    "Please select a booking date.",
                    displayDate, counts, form, model
            );
        }

        if (binding.hasFieldErrors("time") || form.getTime() == null) {
            LocalDate date = form.getDate();
            EmergencyCounts counts = limitService.snapshot(date);

            return withError(
                    "Please select a start time.",
                    date, counts, form, model
            );
        }

        LocalDate date = form.getDate();
        EmergencyCounts counts = limitService.snapshot(date);

        // Basic form checks
        if (!StringUtils.hasText(form.getCustomerEmail())) {
            return withError(
                    "Customer email is required.",
                    date, counts, form, model
            );
        }

        if (!StringUtils.hasText(form.getServiceType())) {
            return withError(
                    "Service type is required.",
                    date, counts, form, model
            );
        }

        if (!ALLOWED_SERVICES.contains(form.getServiceType())) {
            return withError(
                    "Invalid service type.",
                    date, counts, form, model
            );
        }

        // Hard daily limit: nothing can exceed 70 total bookings.
        if (counts.getTotal() >= counts.totalCap()) {
            return withError(
                    "The daily total is full for this date.",
                    date, counts, form, model
            );
        }

        // Customer must exist.
        Optional<User> userOpt =
                userRepository.findByUsername(form.getCustomerEmail());

        if (userOpt.isEmpty()) {
            return withError(
                    "No customer found with that email.",
                    date, counts, form, model
            );
        }

        User customer = userOpt.get();

        // Prevent more than one active booking for the same customer/date.
        boolean alreadyBooked = bookingRepository
                .findByCustomerAndDate(customer, date)
                .stream()
                .anyMatch(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()));

        if (alreadyBooked) {
            return withError(
                    "This customer already has an active booking for this date.",
                    date, counts, form, model
            );
        }

        /*
         * Normal daily capacity = Daycare 40 + Boarding 20 = 60.
         *
         * Below 60:
         *     Admin may create a regular booking from this page,
         *     but must confirm because normal capacity is still available.
         *
         * 60 through 69:
         *     New admin bookings consume an emergency spot.
         */
        int normalDailyCap =
                counts.daycareCap() + counts.boardingCap();

        boolean requiresEmergency =
                counts.getTotal() >= normalDailyCap;

        // Below 60, require explicit admin confirmation.
        if (!requiresEmergency && !form.isForceBooking()) {
            return withConfirmation(
                    "Bookings aren’t full for this day. Book anyway?",
                    date, counts, form, model
            );
        }

        // At 60+, an emergency spot must be available.
        if (requiresEmergency
                && counts.getEmergencyRemaining() <= 0) {

            return withError(
                    "All emergency spots are taken for this date.",
                    date, counts, form, model
            );
        }

        // Create the booking.
        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setServiceType(form.getServiceType());
        booking.setDate(date);
        booking.setTime(form.getTime());
        booking.setStatus("APPROVED");
        booking.setCreatedAt(LocalDateTime.now());

        Booking saved = bookingRepository.save(booking);

        /*
         * Only bookings created after normal daily capacity has
         * already reached 60 consume an emergency allocation.
         */
        if (requiresEmergency) {
            EmergencyAllocation ea = new EmergencyAllocation();
            ea.setDate(date);
            ea.setBookingId(saved.getId());
            ea.setCreatedAt(LocalDateTime.now());
            emergencyRepo.save(ea);
        }

        EmergencyCounts updated = limitService.snapshot(date);

        String bookingType = requiresEmergency
                ? "Emergency booking"
                : "Admin booking";

        return withMessage(
                bookingType + " created for "
                        + customer.getUsername()
                        + " (" + form.getServiceType() + ").",
                date,
                updated,
                new EmergencyForm(date),
                model
        );
    }

    // --------- Helpers ----------

    private String withError(String msg,
                             LocalDate date,
                             EmergencyCounts counts,
                             EmergencyForm form,
                             Model model) {

        model.addAttribute("date", date);
        model.addAttribute("counts", counts);
        model.addAttribute("form", form);
        model.addAttribute("error", msg);
        model.addAttribute("message", null);
        model.addAttribute("confirmationRequired", false);

        return "admin_emergency";
    }

    private String withMessage(String msg,
                               LocalDate date,
                               EmergencyCounts counts,
                               EmergencyForm form,
                               Model model) {

        model.addAttribute("date", date);
        model.addAttribute("counts", counts);
        model.addAttribute("form", form);
        model.addAttribute("message", msg);
        model.addAttribute("error", null);
        model.addAttribute("confirmationRequired", false);

        return "admin_emergency";
    }

    private String withConfirmation(String msg,
                                    LocalDate date,
                                    EmergencyCounts counts,
                                    EmergencyForm form,
                                    Model model) {

        model.addAttribute("date", date);
        model.addAttribute("counts", counts);
        model.addAttribute("form", form);
        model.addAttribute("confirmationMessage", msg);
        model.addAttribute("confirmationRequired", true);
        model.addAttribute("error", null);
        model.addAttribute("message", null);

        return "admin_emergency";
    }

    private LocalDate parseDateOrToday(String dateStr) {
        try {
            if (StringUtils.hasText(dateStr)) {
                return LocalDate.parse(dateStr);
            }
        } catch (Exception ignored) {
        }

        return LocalDate.now();
    }

    @Data
    public static class EmergencyForm {

        @NotNull
        private LocalDate date;

        @NotBlank
        private String customerEmail;

        @NotBlank
        private String serviceType;

        private LocalTime time;

        private boolean forceBooking;

        public EmergencyForm() {
        }

        public EmergencyForm(LocalDate date) {
            this.date = date;
        }
    }
}