package com.dogdaycare.controller;

import com.dogdaycare.dto.BookingRowDto;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.Invoice;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EmergencyAllocationRepository;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.InvoiceRepository;
import com.dogdaycare.service.PricingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/bookings")
public class AdminBookingController {

    private final BookingRepository bookingRepository;
    private final EvaluationRepository evaluationRepository;
    private final EmergencyAllocationRepository emergencyAllocationRepository;

    // NEW: to sync invoices when all days are paid
    private final InvoiceRepository invoiceRepository;
    private final PricingService pricingService;

    public AdminBookingController(BookingRepository bookingRepository,
                                  EvaluationRepository evaluationRepository,
                                  EmergencyAllocationRepository emergencyAllocationRepository,
                                  InvoiceRepository invoiceRepository,
                                  PricingService pricingService) {
        this.bookingRepository = bookingRepository;
        this.evaluationRepository = evaluationRepository;
        this.emergencyAllocationRepository = emergencyAllocationRepository;
        this.invoiceRepository = invoiceRepository;
        this.pricingService = pricingService;
    }

    private LocalDate weekStart(LocalDate any) { return any.with(DayOfWeek.MONDAY); }
    private LocalDate weekEnd(LocalDate ws) { return ws.plusDays(6); }

    // --- JSON used by admin page ---
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

            return new BookingRowDto(
                    b.getId(),
                    customerName,
                    email,                  // <- NEW: customerEmail
                    dogName,
                    b.getServiceType(),
                    b.getTime(),
                    b.getStatus(),
                    b.isWantsAdvancePay(),
                    b.isAdvanceEligible(),
                    b.isPaid()              // <- NEW: paid
            );
        }).collect(Collectors.toList());
    }

    // --- Server view (unchanged) ---
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

        model.addAttribute("date", date);
        model.addAttribute("bookingsDaycare", daycare);
        model.addAttribute("bookingsBoarding", boarding);
        model.addAttribute("activePage", "admin-bookings");
        return "admin/bookings";
    }

    @PostMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id, RedirectAttributes ra) {
        bookingRepository.findById(id).ifPresent(booking -> {
            booking.setStatus("CANCELED");
            bookingRepository.save(booking);
            emergencyAllocationRepository.deleteByBookingId(id);
            ra.addFlashAttribute("successMessage", "Booking canceled.");
        });
        return "redirect:/admin";
    }

    // --- NEW: mark a single booking (day) as PAID ---
    @PostMapping("/mark-paid/{id}")
    public String markDayPaid(@PathVariable Long id, RedirectAttributes ra) {
        bookingRepository.findById(id).ifPresent(b -> {
            // 1) Mark this single booking paid
            if (!"CANCELED".equalsIgnoreCase(b.getStatus()) && !b.isPaid()) {
                b.setPaid(true);
                b.setPaidAt(java.time.LocalDateTime.now());
                bookingRepository.save(b);
            }

            // 2) If *all* non-canceled bookings for this customer in this week are paid,
            //    persist/flip the invoice to paid (and keep it paid forever after).
            var customer = b.getCustomer();
            if (customer != null && customer.getUsername() != null) {
                java.time.LocalDate ws = b.getDate().with(java.time.DayOfWeek.MONDAY);
                java.time.LocalDate we = ws.plusDays(6);

                var weekBookings = bookingRepository.findByDateBetween(ws, we).stream()
                        .filter(x -> x.getCustomer() != null && customer.getId().equals(x.getCustomer().getId()))
                        .filter(x -> !"CANCELED".equalsIgnoreCase(x.getStatus()))
                        .toList();

                boolean allPaid = !weekBookings.isEmpty() && weekBookings.stream().allMatch(com.dogdaycare.model.Booking::isPaid);

                // Load/create invoice for that week+customer
                var invOpt = invoiceRepository.findByCustomerEmailAndWeekStart(customer.getUsername(), ws);
                com.dogdaycare.model.Invoice inv = invOpt.orElseGet(() -> {
                    // amount is a simple sum using current PricingService (we’ll plug in discount tiers next)
                    java.math.BigDecimal amount = weekBookings.stream()
                            .map(x -> pricingService.priceFor(x.getServiceType()))
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                    var evalOpt = evaluationRepository.findTopByEmailOrderByCreatedAtDesc(customer.getUsername());
                    String name = evalOpt.map(com.dogdaycare.model.EvaluationRequest::getClientName).orElse(customer.getUsername());
                    String dog = evalOpt.map(com.dogdaycare.model.EvaluationRequest::getDogName).orElse("N/A");

                    var tmp = new com.dogdaycare.model.Invoice();
                    tmp.setCustomerEmail(customer.getUsername());
                    tmp.setCustomerName(name);
                    tmp.setDogName(dog);
                    tmp.setWeekStart(ws);
                    tmp.setWeekEnd(we);
                    tmp.setAmount(amount);
                    return tmp;
                });

                // Only flip to paid if all bookings are paid; never flip an already-paid invoice back to unpaid
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
}
