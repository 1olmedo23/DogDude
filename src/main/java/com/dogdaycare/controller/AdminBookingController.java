package com.dogdaycare.controller;

import com.dogdaycare.dto.BookingRowDto;
import com.dogdaycare.dto.EmergencyCounts;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.Invoice;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EmergencyAllocationRepository;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.InvoiceRepository;
import com.dogdaycare.service.BookingLimitService;
import com.dogdaycare.service.PricingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.dogdaycare.service.SetForgetService;

import java.math.BigDecimal;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Controller
@RequestMapping("/admin/bookings")
public class AdminBookingController {

    private final BookingRepository bookingRepository;
    private final EvaluationRepository evaluationRepository;
    private final EmergencyAllocationRepository emergencyAllocationRepository;

    // used when marking a day paid (kept as-is)
    private final InvoiceRepository invoiceRepository;
    private final PricingService pricingService;
    private final BookingLimitService bookingLimitService;
    private final SetForgetService setForgetService;

    private static final Set<Integer> ALLOWED_ADJUSTMENTS = Set.of(
            -100, -95, -90, -85, -80, -75, -70, -65, -60, -55,-50,
            -45, -40, -35, -30, -25, -20, -15, -10, -5,
            0,
            5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100
    );

    public AdminBookingController(BookingRepository bookingRepository,
                                  EvaluationRepository evaluationRepository,
                                  EmergencyAllocationRepository emergencyAllocationRepository,
                                  InvoiceRepository invoiceRepository,
                                  PricingService pricingService,
                                  BookingLimitService bookingLimitService,
                                  SetForgetService setForgetService) {
        this.bookingRepository = bookingRepository;
        this.evaluationRepository = evaluationRepository;
        this.emergencyAllocationRepository = emergencyAllocationRepository;
        this.invoiceRepository = invoiceRepository;
        this.pricingService = pricingService;
        this.bookingLimitService = bookingLimitService;
        this.setForgetService = setForgetService;
    }

    private LocalDate weekStart(LocalDate any) { return any.with(DayOfWeek.MONDAY); }
    private LocalDate weekEnd(LocalDate ws) { return ws.plusDays(6); }

    // ---------------- JSON consumed by admin page (Bookings tab) ----------------
    @GetMapping
    @ResponseBody
    public List<BookingRowDto> getBookingsByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<Booking> bookings = bookingRepository.findByDate(date);

        return bookings.stream().map(b -> {
            String email = (b.getCustomer() != null) ? b.getCustomer().getUsername() : "N/A";

            Optional<EvaluationRequest> evalOpt =
                    (email == null || "N/A".equals(email))
                            ? Optional.empty()
                            : evaluationRepository.findTopByEmailOrderByCreatedAtDesc(email);

            String customerName = evalOpt.map(EvaluationRequest::getClientName).orElse(email != null ? email : "N/A");
            String dogName = evalOpt.map(EvaluationRequest::getDogName).orElse("N/A");

            // IMPORTANT: include both the historical lock and the current live (tier-aware) amount
            return new BookingRowDto(
                    b.getId(),
                    customerName,
                    email,
                    dogName,
                    b.getServiceType(),
                    b.getTime(),
                    b.getStatus(),
                    b.isWantsAdvancePay(),
                    b.isAdvanceEligible(),
                    b.isPaid(),
                    b.getQuotedRateAtLock(),
                    b.getDogCount(),
                    finalAmountFor(b),
                    b.getManualAdjustmentAmount(),
                    b.getManualAdjustmentReason()
            );
        }).toList();
    }

    // ---------------- Optional server-side view (unchanged) ----------------
    @GetMapping("/view")
    public String viewByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model
    ) {
        List<Booking> bookings = bookingRepository.findByDate(date);

        var daycare = bookings.stream()
                .filter(b -> b.getServiceType() != null && b.getServiceType().toLowerCase().contains("daycare"))
                .sorted(Comparator.comparing(Booking::getTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        var boarding = bookings.stream()
                .filter(b -> b.getServiceType() != null && b.getServiceType().toLowerCase().contains("boarding"))
                .sorted(Comparator.comparing(Booking::getTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        var afterHours = bookings.stream()
                .filter(b -> b.getServiceType() != null &&
                        b.getServiceType().toLowerCase().contains("after hours"))
                .sorted(Comparator.comparing(Booking::getTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        model.addAttribute("date", date);
        model.addAttribute("bookingsDaycare", daycare);
        model.addAttribute("bookingsBoarding", boarding);
        model.addAttribute("bookingsAfterHours", afterHours);
        model.addAttribute("activePage", "admin-bookings");
        return "admin/bookings";
    }

    // capacity ribbon proxy (unchanged)
    @GetMapping("/capacity")
    @ResponseBody
    public EmergencyCounts capacity(@RequestParam("date")
                                    @org.springframework.format.annotation.DateTimeFormat(iso =
                                            org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                    java.time.LocalDate date) {
        return bookingLimitService.snapshot(date);
    }

    @PostMapping("/adjust/{id}")
    public String adjustBookingAmount(@PathVariable Long id,
                                      @RequestParam("amount") Integer amount,
                                      @RequestParam(value = "reason", required = false) String reason,
                                      RedirectAttributes ra) {

        if (amount == null || !ALLOWED_ADJUSTMENTS.contains(amount)) {
            ra.addFlashAttribute(
                    "errorMessage",
                    "Adjustment must be between -100 and 100 in $5 increments."
            );
            return "redirect:/admin#bookings";
        }

        String cleanReason = (reason == null) ? "" : reason.trim();

        if (amount != 0 && cleanReason.isBlank()) {
            ra.addFlashAttribute("errorMessage", "Please enter a short reason for the adjustment.");
            return "redirect:/admin#bookings";
        }

        if (cleanReason.length() > 120) {
            ra.addFlashAttribute("errorMessage", "Adjustment reason must be 120 characters or fewer.");
            return "redirect:/admin#bookings";
        }

        bookingRepository.findById(id).ifPresentOrElse(booking -> {
            booking.setManualAdjustmentAmount(BigDecimal.valueOf(amount).setScale(2));
            booking.setManualAdjustmentReason(cleanReason.isBlank() ? null : cleanReason);
            booking.setManualAdjustmentUpdatedAt(LocalDateTime.now());
            bookingRepository.save(booking);

            if (amount == 0) {
                ra.addFlashAttribute("successMessage", "Booking adjustment cleared.");
            } else {
                ra.addFlashAttribute("successMessage", "Booking adjustment updated.");
            }
        }, () -> ra.addFlashAttribute("errorMessage", "Booking not found."));

        return "redirect:/admin#bookings";
    }

    @PostMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id, RedirectAttributes ra) {
        bookingRepository.findById(id).ifPresent(booking -> {
            booking.setStatus("CANCELED");
            bookingRepository.save(booking);

            setForgetService.addExceptionForBookingIfNeeded(booking, "ADMIN_CANCEL");

            emergencyAllocationRepository.deleteByBookingId(id);
            ra.addFlashAttribute("successMessage", "Booking canceled.");
        });
        return "redirect:/admin";
    }

    // Mark a single booking (day) as PAID (unchanged logic)
    @PostMapping("/mark-paid/{id}")
    public String markDayPaid(@PathVariable Long id, RedirectAttributes ra) {
        bookingRepository.findById(id).ifPresent(b -> {
            // 1) mark this single booking paid
            if (!"CANCELED".equalsIgnoreCase(b.getStatus()) && !b.isPaid()) {
                b.setPaid(true);
                b.setPaidAt(java.time.LocalDateTime.now());
                bookingRepository.save(b);
            }

            // 2) if all non-canceled bookings for this customer/week are paid, flip invoice to paid
            var customer = b.getCustomer();
            if (customer != null && customer.getUsername() != null) {
                LocalDate ws = b.getDate().with(DayOfWeek.MONDAY);
                LocalDate we = ws.plusDays(6);

                var weekBookings = bookingRepository.findByDateBetween(ws, we).stream()
                        .filter(x -> x.getCustomer() != null && customer.getId().equals(x.getCustomer().getId()))
                        .filter(x -> !"CANCELED".equalsIgnoreCase(x.getStatus()))
                        .toList();

                boolean allPaid = !weekBookings.isEmpty() && weekBookings.stream().allMatch(Booking::isPaid);

                var invOpt = invoiceRepository.findByCustomerEmailAndWeekStart(customer.getUsername(), ws);

                Invoice inv = invOpt.orElseGet(() -> {
                    // snapshot current week amount for this customer (using priceFor×dogCount)
                    var amount = weekBookings.stream()
                            .map(bk -> {
                                BigDecimal perDog = pricingService.priceFor(bk);
                                int n = (bk.getDogCount() != null ? bk.getDogCount() : 1);
                                return perDog.multiply(BigDecimal.valueOf(n));
                            })
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    Invoice i = new Invoice();
                    i.setCustomerEmail(customer.getUsername());
                    i.setCustomerName(customer.getUsername()); // keep simple (or look up Evaluation)
                    i.setDogName("N/A");
                    i.setWeekStart(ws);
                    i.setWeekEnd(we);
                    i.setAmount(amount);
                    return i;
                });

                if (allPaid && !inv.isPaid()) {
                    inv.setPaid(true);
                    inv.setPaidAt(java.time.LocalDateTime.now());
                }

                invoiceRepository.save(inv);
            }
        });

        ra.addFlashAttribute("successMessage", "Booking marked paid.");
        return "redirect:/admin#bookings";
    }

    @PostMapping("/revert-paid/{id}")
    public String revertBookingPaid(@PathVariable Long id, RedirectAttributes ra) {
        Booking booking = bookingRepository.findById(id).orElse(null);

        if (booking == null) {
            ra.addFlashAttribute("errorMessage", "Booking not found.");
            return "redirect:/admin#bookings";
        }

        booking.setPaid(false);
        booking.setPaidAt(null);
        bookingRepository.save(booking);

        ra.addFlashAttribute("successMessage", "Booking payment reverted.");
        return "redirect:/admin#bookings";
    }

    private BigDecimal finalAmountFor(Booking b) {
        BigDecimal base = baseAmountFor(b);
        BigDecimal adjustment = normalizedAdjustment(b.getManualAdjustmentAmount());
        return base.add(adjustment);
    }

    private BigDecimal normalizedAdjustment(BigDecimal adjustment) {
        return adjustment == null ? BigDecimal.ZERO : adjustment;
    }

    // ---------------- baseAmountFor ----------------
    private BigDecimal baseAmountFor(Booking b) {

        // If we have a locked price, ALWAYS use it for display.
        // NOTE: quoted_rate_at_lock in your DB is already the TOTAL for the booking
        // (it already reflects dog_count), so DO NOT multiply by dogs again.
        if (b.getQuotedRateAtLock() != null) {
            return b.getQuotedRateAtLock();
        }

        // Fallback only if older rows exist with no locked price.
        int dogs = (b.getDogCount() != null ? b.getDogCount() : 1);

        String svc = (b.getServiceType() == null ? "" : b.getServiceType()).toLowerCase();
        boolean isDaycare = svc.contains("daycare");
        boolean isAfterHours = svc.contains("after hours");
        boolean isBoarding = svc.contains("boarding");

        if (isAfterHours) {
            return new BigDecimal("90.00").multiply(BigDecimal.valueOf(dogs));
        }

        if (isBoarding) {
            BigDecimal base = pricingService.priceFor(b); // your boarding logic
            return base.multiply(BigDecimal.valueOf(dogs));
        }

        if (isDaycare) {
            var customer = b.getCustomer();
            if (customer == null) return BigDecimal.ZERO;

            LocalDate ws = pricingService.weekStartMonday(b.getDate());
            LocalDate we = ws.plusDays(6);

            var weekBookings = bookingRepository.findByCustomerAndDateBetween(customer, ws, we).stream()
                    .filter(x -> x.getServiceType() != null && x.getServiceType().toLowerCase().contains("daycare"))
                    .filter(x -> !"CANCELED".equalsIgnoreCase(x.getStatus()))
                    .toList();

            boolean atLeast4 = weekBookings.size() >= 4;

            BigDecimal perDog = pricingService.quoteDaycareAtTier(b, atLeast4);
            return perDog.multiply(BigDecimal.valueOf(dogs));
        }

        BigDecimal base = pricingService.priceFor(b);
        return base.multiply(BigDecimal.valueOf(dogs));
    }
}
