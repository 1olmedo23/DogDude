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

import java.math.BigDecimal;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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

    public AdminBookingController(BookingRepository bookingRepository,
                                  EvaluationRepository evaluationRepository,
                                  EmergencyAllocationRepository emergencyAllocationRepository,
                                  InvoiceRepository invoiceRepository,
                                  PricingService pricingService,
                                  BookingLimitService bookingLimitService) {
        this.bookingRepository = bookingRepository;
        this.evaluationRepository = evaluationRepository;
        this.emergencyAllocationRepository = emergencyAllocationRepository;
        this.invoiceRepository = invoiceRepository;
        this.pricingService = pricingService;
        this.bookingLimitService = bookingLimitService;
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
                    liveAmountFor(b) // << used by custom.js price chip
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

    // ---------------- Live tier-aware per-booking total for Admin chip ----------------
    private BigDecimal liveAmountFor(Booking b) {
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
            // Determine customer's current week tier (>=4 daycare bookings, non-canceled)
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

        // fallback
        BigDecimal base = pricingService.priceFor(b);
        return base.multiply(BigDecimal.valueOf(dogs));
    }
}
