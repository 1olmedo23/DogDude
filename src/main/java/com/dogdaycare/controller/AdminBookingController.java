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
import com.dogdaycare.model.User;
import com.dogdaycare.service.PricingRules;
import java.time.OffsetDateTime;

import java.math.BigDecimal;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import jakarta.transaction.Transactional;

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
    private String bookingsRedirect(LocalDate date) {
        if (date == null) {
            return "redirect:/admin#bookings";
        }

        return "redirect:/admin?date=" + date + "#bookings";
    }

    // ---------------- JSON consumed by admin page (Bookings tab) ----------------
    @GetMapping
    @ResponseBody
    public List<BookingRowDto> getBookingsByDate(
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<Booking> bookings = bookingRepository.findByDate(date);

        /*
         * Customers who currently have a non-canceled booking for this date.
         *
         * If one of these customers also has an older canceled booking for the
         * same date, the canceled booking is kept in the database but hidden
         * from the admin Bookings tab.
         */
        Set<Long> customersWithActiveBooking = bookings.stream()
                .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                .filter(b -> b.getCustomer() != null && b.getCustomer().getId() != null)
                .map(b -> b.getCustomer().getId())
                .collect(java.util.stream.Collectors.toSet());

        return bookings.stream()

                // Keep all active bookings.
                // Keep a canceled booking only when the customer has not
                // rebooked another active service for this same date.
                .filter(b -> {
                    if (!"CANCELED".equalsIgnoreCase(b.getStatus())) {
                        return true;
                    }

                    if (b.getCustomer() == null || b.getCustomer().getId() == null) {
                        return true;
                    }

                    return !customersWithActiveBooking.contains(b.getCustomer().getId());
                })

                .sorted(
                        Comparator.comparing(
                                        Booking::getTime,
                                        Comparator.nullsLast(Comparator.naturalOrder())
                                )
                                .thenComparing(
                                        Booking::getId,
                                        Comparator.nullsLast(Comparator.naturalOrder())
                                )
                )
                .map(b -> {
                    String email = (b.getCustomer() != null)
                            ? b.getCustomer().getUsername()
                            : "N/A";

                    Optional<EvaluationRequest> evalOpt =
                            (email == null || "N/A".equals(email))
                                    ? Optional.empty()
                                    : evaluationRepository.findTopByEmailOrderByCreatedAtDesc(email);

                    String customerName = evalOpt
                            .map(EvaluationRequest::getClientName)
                            .orElse(email != null ? email : "N/A");

                    String dogName = evalOpt
                            .map(EvaluationRequest::getDogName)
                            .orElse("N/A");

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
                })
                .toList();
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
                                      @RequestParam(value = "date", required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      RedirectAttributes ra) {

        if (amount == null || !ALLOWED_ADJUSTMENTS.contains(amount)) {
            ra.addFlashAttribute(
                    "errorMessage",
                    "Adjustment must be between -100 and 100 in $5 increments."
            );
            return bookingsRedirect(date);
        }

        String cleanReason = (reason == null) ? "" : reason.trim();

        if (amount != 0 && cleanReason.isBlank()) {
            ra.addFlashAttribute("errorMessage", "Please enter a short reason for the adjustment.");
            return bookingsRedirect(date);
        }

        if (cleanReason.length() > 120) {
            ra.addFlashAttribute("errorMessage", "Adjustment reason must be 120 characters or fewer.");
            return bookingsRedirect(date);
        }

        bookingRepository.findById(id).ifPresentOrElse(booking -> {

            // Prevent changes to finalized financial records.
            if (booking.isPaid()) {
                ra.addFlashAttribute(
                        "errorMessage",
                        "Cannot adjust a paid booking. " +
                                "Revert the payment before making adjustments."
                );
                return;
            }

            booking.setManualAdjustmentAmount(
                    BigDecimal.valueOf(amount).setScale(2)
            );

            booking.setManualAdjustmentReason(
                    cleanReason.isBlank() ? null : cleanReason
            );

            booking.setManualAdjustmentUpdatedAt(
                    LocalDateTime.now()
            );

            bookingRepository.save(booking);

            if (amount == 0) {
                ra.addFlashAttribute(
                        "successMessage",
                        "Booking adjustment cleared."
                );
            } else {
                ra.addFlashAttribute(
                        "successMessage",
                        "Booking adjustment updated."
                );
            }

        }, () -> ra.addFlashAttribute(
                "errorMessage",
                "Booking not found."
        ));

        return bookingsRedirect(date);
    }

    @PostMapping("/cancel/{id}")
    @Transactional
    public String cancelBooking(
            @PathVariable Long id,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            RedirectAttributes ra) {
        bookingRepository.findById(id).ifPresent(booking -> {
            setForgetService.addExceptionForBookingIfNeeded(
                    booking, "ADMIN_CANCEL"
            );

            booking.setStatus("CANCELED");
            bookingRepository.save(booking);

            emergencyAllocationRepository.deleteByBookingId(id);
            ra.addFlashAttribute("successMessage", "Booking canceled.");
        });
        return bookingsRedirect(date);
    }

    /**
     * Finalizes an individual booking's current price
     * before marking it paid.
     *
     * Keeps the weekly invoice total synchronized
     * without changing previously paid booking prices.
     */
    @PostMapping("/mark-paid/{id}")
    @Transactional
    public String markDayPaid(
            @PathVariable Long id,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            RedirectAttributes ra) {

        Booking booking = bookingRepository.findById(id).orElse(null);

        if (booking == null) {
            ra.addFlashAttribute("errorMessage", "Booking not found.");
            return bookingsRedirect(date);
        }

        if ("CANCELED".equalsIgnoreCase(booking.getStatus())) {
            ra.addFlashAttribute(
                    "errorMessage",
                    "Canceled bookings cannot be marked paid."
            );
            return bookingsRedirect(date);
        }

        User customer = booking.getCustomer();

        if (customer == null || customer.getUsername() == null) {
            ra.addFlashAttribute(
                    "errorMessage",
                    "Booking has no valid customer."
            );
            return bookingsRedirect(date);
        }

        if (!booking.isPaid()) {

            // Calculate the current TOTAL while still unpaid.
            BigDecimal baseTotal =
                    pricingService.baseTotalFor(booking);

            // Store the full booking total, excluding
            // the separately stored manual adjustment.
            booking.setQuotedRateAtLock(baseTotal);

            boolean prepaidDaycare =
                    PricingRules.isStandardDaycare(
                            booking.getServiceType())
                            && booking.isAdvanceEligible()
                            && booking.isWantsAdvancePay();

            booking.setInPrepayBundle(prepaidDaycare);

            if (prepaidDaycare) {
                booking.setBundleLockedAt(
                        OffsetDateTime.now()
                );
            }

            // Only mark paid after finalizing pricing.
            booking.setPaid(true);
            booking.setPaidAt(LocalDateTime.now());

            bookingRepository.save(booking);
        }

        LocalDate ws =
                pricingService.weekStartMonday(booking.getDate());

        LocalDate we =
                pricingService.weekEndSunday(booking.getDate());

        List<Booking> weekBookings = bookingRepository
                .findByCustomerAndDateBetweenAndStatusNotIgnoreCase(
                        customer,
                        ws,
                        we,
                        "CANCELED"
                );

        boolean allPaid = !weekBookings.isEmpty()
                && weekBookings.stream().allMatch(Booking::isPaid);

        // Calculate each booking's full total exactly once.
        // Paid bookings retain their locked base amounts.
        BigDecimal weeklyTotal = weekBookings.stream()
                .map(pricingService::finalTotalFor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Invoice invoice = invoiceRepository
                .findByCustomerEmailAndWeekStart(
                        customer.getUsername(),
                        ws
                )
                .orElseGet(() -> {
                    Invoice created = new Invoice();
                    created.setCustomerEmail(customer.getUsername());
                    created.setCustomerName(customer.getUsername());
                    created.setDogName("N/A");
                    created.setWeekStart(ws);
                    created.setWeekEnd(we);
                    return created;
                });

        invoice.setAmount(weeklyTotal);

        if (allPaid && !invoice.isPaid()) {
            invoice.setPaid(true);
            invoice.setPaidAt(LocalDateTime.now());
        }

        invoiceRepository.save(invoice);

        ra.addFlashAttribute(
                "successMessage",
                "Booking marked paid."
        );

        return bookingsRedirect(date);
    }

    /**
     * Reverts an individual booking payment and
     * synchronizes the weekly invoice status.
     *
     * Preserves the finalized booking amount
     * and any manual adjustments.
     */
    @PostMapping("/revert-paid/{id}")
    @Transactional
    public String revertBookingPaid(
            @PathVariable Long id,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            RedirectAttributes ra) {

        Booking booking = bookingRepository
                .findById(id)
                .orElse(null);

        if (booking == null) {
            ra.addFlashAttribute(
                    "errorMessage",
                    "Booking not found."
            );
            return bookingsRedirect(date);
        }

        if (!booking.isPaid()) {
            ra.addFlashAttribute(
                    "errorMessage",
                    "Booking is already unpaid."
            );
            return bookingsRedirect(date);
        }

        // Revert payment status only.
        // Preserve the previously finalized price.
        booking.setPaid(false);
        booking.setPaidAt(null);

        bookingRepository.save(booking);

        User customer = booking.getCustomer();

        if (customer != null
                && customer.getUsername() != null) {

            LocalDate ws =
                    pricingService.weekStartMonday(
                            booking.getDate()
                    );

            invoiceRepository
                    .findByCustomerEmailAndWeekStart(
                            customer.getUsername(),
                            ws
                    )
                    .ifPresent(invoice -> {

                        // The week is no longer fully paid.
                        invoice.setPaid(false);
                        invoice.setPaidAt(null);

                        // Preserve the existing invoice amount
                        // and finalized booking prices.
                        invoiceRepository.save(invoice);
                    });
        }

        ra.addFlashAttribute(
                "successMessage",
                "Booking payment reverted."
        );

        return bookingsRedirect(date);
    }

    /**
     * Returns the booking's total amount including
     * any admin adjustment.
     *
     * Unpaid bookings use current pricing rules.
     * Paid bookings use their saved finalized base total.
     */
    private BigDecimal finalAmountFor(Booking booking) {
        return pricingService.finalTotalFor(booking);
    }
}
