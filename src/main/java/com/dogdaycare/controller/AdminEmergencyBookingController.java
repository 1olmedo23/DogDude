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

@Controller
@RequestMapping("/admin/emergency")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','EMPLOYEE')")
public class AdminEmergencyBookingController {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EmergencyAllocationRepository emergencyRepo;
    private final BookingLimitService limitService;

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
        return "admin_emergency";
    }

    // --------- POST: create emergency booking ----------
    @PostMapping
    public String submit(@ModelAttribute("form") EmergencyForm form,
                         BindingResult binding,
                         Model model) {

        LocalDate date = (form.getDate() != null) ? form.getDate() : LocalDate.now();
        EmergencyCounts counts = limitService.snapshot(date);

        // Basic form checks
        if (!StringUtils.hasText(form.getCustomerEmail())) {
            return withError("Customer email is required.", date, counts, form, model);
        }
        if (!StringUtils.hasText(form.getServiceType())) {
            return withError("Service type is required.", date, counts, form, model);
        }

        // 1) Daily total cap (includes emergency)
        if (counts.getTotal() >= counts.totalCap()) {
            return withError("The daily total (including emergency) is full for this date.", date, counts, form, model);
        }

        // 2) Customer must exist
        Optional<User> userOpt = userRepository.findByUsername(form.getCustomerEmail());
        if (userOpt.isEmpty()) {
            return withError("No customer found with that email.", date, counts, form, model);
        }
        User customer = userOpt.get();

        // 3) Emergency can be used only if the selected service's normal capacity is already full
        String svc = form.getServiceType().toLowerCase();
        boolean isDaycare = svc.contains("daycare");
        boolean isBoarding = svc.contains("boarding");

        if (isDaycare && counts.getDaycare() < counts.daycareCap()) {
            return withError("Daycare normal capacity isn’t full yet. Please use the standard booking flow.", date, counts, form, model);
        }
        if (isBoarding && counts.getBoarding() < counts.boardingCap()) {
            return withError("Boarding normal capacity isn’t full yet. Please use the standard booking flow.", date, counts, form, model);
        }

        // 4) Emergency pool availability
        if (counts.getEmergencyRemaining() <= 0) {
            return withError("All emergency spots are taken for this date.", date, counts, form, model);
        }

        // Create booking (admin placed)
        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setServiceType(form.getServiceType());
        booking.setDate(date);
        booking.setTime(form.getTime() != null ? form.getTime() : LocalTime.of(6, 0));
        booking.setStatus("APPROVED");

        Booking saved = bookingRepository.save(booking);

        // Optional audit row (kept for traceability; not used for capacity math)
        EmergencyAllocation ea = new EmergencyAllocation();
        ea.setDate(date);
        ea.setBookingId(saved.getId());
        ea.setCreatedAt(LocalDateTime.now());
        emergencyRepo.save(ea);

        // Refresh counts for UI after insert
        EmergencyCounts updated = limitService.snapshot(date);
        return withMessage(
                "Emergency booking created for " + customer.getUsername() + " (" + form.getServiceType() + ").",
                date, updated, new EmergencyForm(date), model
        );
    }

    // --------- Helpers ----------

    private String withError(String msg, LocalDate date,
                             EmergencyCounts counts,
                             EmergencyForm form, Model model) {
        model.addAttribute("date", date);
        model.addAttribute("counts", counts);
        model.addAttribute("form", form);
        model.addAttribute("error", msg);
        model.addAttribute("message", null);
        return "admin_emergency";
    }

    private String withMessage(String msg, LocalDate date,
                               EmergencyCounts counts,
                               EmergencyForm form, Model model) {
        model.addAttribute("date", date);
        model.addAttribute("counts", counts);
        model.addAttribute("form", form);
        model.addAttribute("message", msg);
        model.addAttribute("error", null);
        return "admin_emergency";
    }

    private LocalDate parseDateOrToday(String dateStr) {
        try {
            if (StringUtils.hasText(dateStr)) return LocalDate.parse(dateStr);
        } catch (Exception ignored) {}
        return LocalDate.now();
    }

    @Data
    public static class EmergencyForm {
        @NotNull
        private LocalDate date;

        @NotBlank
        private String customerEmail;

        @NotBlank
        private String serviceType; // "Daycare (6 AM - 3 PM)" | "Daycare (6 AM - 8 PM)" | "Boarding"

        private LocalTime time;

        public EmergencyForm() {}
        public EmergencyForm(LocalDate date) { this.date = date; }
    }
}
