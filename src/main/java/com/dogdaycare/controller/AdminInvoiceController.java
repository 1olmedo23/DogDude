package com.dogdaycare.controller;

import com.dogdaycare.dto.InvoiceRowDto;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.Invoice;
import com.dogdaycare.model.User;
import com.dogdaycare.model.WeeklyBillingStatus;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.InvoiceRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.repository.WeeklyBillingStatusRepository;
import com.dogdaycare.service.PricingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/invoices")
public class AdminInvoiceController {

    private final BookingRepository bookingRepository;
    private final EvaluationRepository evaluationRepository;
    private final InvoiceRepository invoiceRepository;
    private final PricingService pricingService;

    // NEW: to sync paid state across tabs
    private final UserRepository userRepository;
    private final WeeklyBillingStatusRepository weeklyRepo;

    public AdminInvoiceController(BookingRepository bookingRepository,
                                  EvaluationRepository evaluationRepository,
                                  InvoiceRepository invoiceRepository,
                                  PricingService pricingService,
                                  UserRepository userRepository,
                                  WeeklyBillingStatusRepository weeklyRepo) {
        this.bookingRepository = bookingRepository;
        this.evaluationRepository = evaluationRepository;
        this.invoiceRepository = invoiceRepository;
        this.pricingService = pricingService;
        this.userRepository = userRepository;
        this.weeklyRepo = weeklyRepo;
    }

    private LocalDate weekStart(LocalDate any) {
        return any.with(DayOfWeek.MONDAY);
    }
    private LocalDate weekEnd(LocalDate start) {
        return start.plusDays(6);
    }
    private LocalDate lastCompletedWeekStart() {
        LocalDate today = LocalDate.now();
        return today.with(DayOfWeek.MONDAY).minusWeeks(1);
    }

    @GetMapping
    public String invoicingPage(Model model) {
        return "admin";
    }

    @GetMapping("/weekly")
    @ResponseBody
    public List<InvoiceRowDto> weekly(
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {

        LocalDate ws = (start != null) ? start.with(DayOfWeek.MONDAY) : lastCompletedWeekStart();
        LocalDate we = weekEnd(ws);

        List<Booking> weekBookings = bookingRepository.findByDateBetween(ws, we).stream()
                .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                .collect(Collectors.toList());

        Map<String, List<Booking>> byEmail = weekBookings.stream()
                .filter(b -> b.getCustomer() != null && b.getCustomer().getUsername() != null)
                .collect(Collectors.groupingBy(b -> b.getCustomer().getUsername()));

        List<InvoiceRowDto> rows = new ArrayList<>();
        for (var entry : byEmail.entrySet()) {
            String email = entry.getKey();
            var bookings = entry.getValue();

            var evalOpt = evaluationRepository.findTopByEmailOrderByCreatedAtDesc(email);
            String name = evalOpt.map(EvaluationRequest::getClientName).orElse(email);
            String dog  = evalOpt.map(EvaluationRequest::getDogName).orElse("N/A");

            var amount = bookings.stream()
                    .map(b -> pricingService.priceFor(b.getServiceType()))
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

            boolean allDaysPaid = !bookings.isEmpty() && bookings.stream().allMatch(Booking::isPaid);

            var invOpt = invoiceRepository.findByCustomerEmailAndWeekStart(email, ws);
            boolean invoicePaid = invOpt.map(Invoice::isPaid).orElse(false);

            boolean rowPaid = invoicePaid || allDaysPaid;

            // prefer persisted invoice values when present
            if (invOpt.isPresent()) {
                var inv = invOpt.get();
                rows.add(new com.dogdaycare.dto.InvoiceRowDto(
                        inv.getId(), name, email, dog, inv.getAmount(), inv.isPaid()
                ));
            } else {
                rows.add(new com.dogdaycare.dto.InvoiceRowDto(
                        null, name, email, dog, amount, rowPaid
                ));
            }
        }

        rows.sort(Comparator.comparing(com.dogdaycare.dto.InvoiceRowDto::getCustomerName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    @PostMapping("/mark-paid")
    public String markPaid(
            @RequestParam("email") String customerEmail,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            RedirectAttributes ra
    ) {
        LocalDate ws = weekStart(start);
        LocalDate we = weekEnd(ws);

        Invoice invoice = invoiceRepository.findByCustomerEmailAndWeekStart(customerEmail, ws)
                .orElseGet(() -> {
                    List<Booking> bookings = bookingRepository.findByDateBetween(ws, we).stream()
                            .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                            .filter(b -> b.getCustomer() != null && customerEmail.equals(b.getCustomer().getUsername()))
                            .collect(Collectors.toList());

                    var amount = bookings.stream()
                            .map(b -> pricingService.priceFor(b.getServiceType()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    var evalOpt = evaluationRepository.findTopByEmailOrderByCreatedAtDesc(customerEmail);
                    String name = evalOpt.map(EvaluationRequest::getClientName).orElse(customerEmail);
                    String dog  = evalOpt.map(EvaluationRequest::getDogName).orElse("N/A");

                    Invoice inv = new Invoice();
                    inv.setCustomerEmail(customerEmail);
                    inv.setCustomerName(name);
                    inv.setDogName(dog);
                    inv.setWeekStart(ws);
                    inv.setWeekEnd(we);
                    inv.setAmount(amount);
                    return inv;
                });

        if (!invoice.isPaid()) {
            invoice.setPaid(true);
            invoice.setPaidAt(LocalDateTime.now());
            invoiceRepository.save(invoice);

            // *** NEW: mark all bookings for this week/customer as PAID
            List<Booking> weekCustomerBookings = bookingRepository.findByDateBetween(ws, we).stream()
                    .filter(b -> b.getCustomer() != null && customerEmail.equals(b.getCustomer().getUsername()))
                    .collect(Collectors.toList());
            for (Booking b : weekCustomerBookings) {
                if (!"CANCELED".equalsIgnoreCase(b.getStatus())) {
                    b.setPaid(true);
                    b.setPaidAt(LocalDateTime.now());
                }
            }
            bookingRepository.saveAll(weekCustomerBookings);

            ra.addFlashAttribute("invoiceMessage", "Invoice marked paid and locked. All week bookings marked paid.");
        } else {
            ra.addFlashAttribute("invoiceMessage", "Invoice is already paid.");
        }
        return "redirect:/admin#invoicing";
    }
}

