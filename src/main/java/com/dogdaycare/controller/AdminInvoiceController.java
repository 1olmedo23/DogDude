package com.dogdaycare.controller;

import com.dogdaycare.dto.InvoiceRowDto;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.Invoice;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.InvoiceRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.repository.WeeklyBillingStatusRepository;
import com.dogdaycare.service.BundleService;
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

    private final UserRepository userRepository;
    private final WeeklyBillingStatusRepository weeklyRepo;

    // Kept injected but not used for locking anymore
    private final BundleService bundleService;

    public AdminInvoiceController(BookingRepository bookingRepository,
                                  EvaluationRepository evaluationRepository,
                                  InvoiceRepository invoiceRepository,
                                  PricingService pricingService,
                                  UserRepository userRepository,
                                  WeeklyBillingStatusRepository weeklyRepo,
                                  BundleService bundleService) {
        this.bookingRepository = bookingRepository;
        this.evaluationRepository = evaluationRepository;
        this.invoiceRepository = invoiceRepository;
        this.pricingService = pricingService;
        this.userRepository = userRepository;
        this.weeklyRepo = weeklyRepo;
        this.bundleService = bundleService;
    }

    private LocalDate weekStart(LocalDate any) { return any.with(DayOfWeek.MONDAY); }
    private LocalDate weekEnd(LocalDate start) { return start.plusDays(6); }
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

            // Live total from current bookings (non-canceled)
            BigDecimal currentAmount = bookings.stream()
                    .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                    .map(pricingService::priceFor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

// All bookings in this week are paid?
            boolean allDaysPaid = !bookings.isEmpty() && bookings.stream().allMatch(Booking::isPaid);

// Invoice record (may or may not exist)
            var invOpt = invoiceRepository.findByCustomerEmailAndWeekStart(email, ws);
            boolean invoicePaid = invOpt.map(Invoice::isPaid).orElse(false);

// NEW: paid-to-date = sum of PAID bookings in this week (non-canceled)
            BigDecimal paidToDate = bookings.stream()
                    .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                    .filter(Booking::isPaid)
                    .map(pricingService::priceFor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

// NEW: deltaUnpaid = what's still owed in this week
            BigDecimal deltaUnpaid = currentAmount.subtract(paidToDate);
            if (deltaUnpaid.signum() < 0) deltaUnpaid = BigDecimal.ZERO;

// Row is paid ONLY if invoicePaid AND all current bookings are paid
            boolean rowPaid = invoicePaid && allDaysPaid;

            Long invoiceId = invOpt.map(Invoice::getId).orElse(null);

            rows.add(new InvoiceRowDto(
                    invoiceId,
                    name,
                    email,
                    dog,
                    currentAmount,     // total(live)
                    rowPaid,
                    paidToDate,        // paid to date (previouslyPaidAmount field)
                    deltaUnpaid,       // new since paid
                    invoicePaid
            ));
        }

        rows.sort(Comparator.comparing(InvoiceRowDto::getCustomerName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    @PostMapping("/mark-paid")
    public String markPaid(
            @RequestParam(value = "invoiceId", required = false) Long invoiceId,
            @RequestParam(value = "email", required = false) String customerEmail,
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            RedirectAttributes ra
    ) {
        // Resolve inputs
        Invoice invoice = null;
        if (invoiceId != null) {
            invoice = invoiceRepository.findById(invoiceId).orElse(null);
            if (invoice == null) {
                ra.addFlashAttribute("invoiceMessage", "Invoice not found.");
                return "redirect:/admin#invoicing";
            }
            customerEmail = invoice.getCustomerEmail();
            start = invoice.getWeekStart();
        }

        if (customerEmail == null || start == null) {
            ra.addFlashAttribute("invoiceMessage", "Missing invoice parameters.");
            return "redirect:/admin#invoicing";
        }

        LocalDate ws = weekStart(start);
        LocalDate we = weekEnd(ws);

        // Load or build invoice (we'll set amount after marking bookings)
        if (invoice == null) {
            invoice = invoiceRepository.findByCustomerEmailAndWeekStart(customerEmail, ws).orElse(null);
        }
        if (invoice == null) {
            var evalOpt = evaluationRepository.findTopByEmailOrderByCreatedAtDesc(customerEmail);
            String name = evalOpt.map(EvaluationRequest::getClientName).orElse(customerEmail);
            String dog  = evalOpt.map(EvaluationRequest::getDogName).orElse("N/A");

            invoice = new Invoice();
            invoice.setCustomerEmail(customerEmail);
            invoice.setCustomerName(name);
            invoice.setDogName(dog);
            invoice.setWeekStart(ws);
            invoice.setWeekEnd(we);
        }

        // Fetch all non-canceled bookings for that customer/week
        final String emailKey = customerEmail; // make effectively final for lambdas
        List<Booking> weekCustomerBookings = bookingRepository.findByDateBetween(ws, we).stream()
                .filter(b -> b.getCustomer() != null && emailKey.equals(b.getCustomer().getUsername()))
                .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                .collect(Collectors.toList());

        if (!invoice.isPaid()) {
            // First-time payment: mark ALL week bookings paid
            for (Booking b : weekCustomerBookings) {
                if (!b.isPaid()) {
                    b.setPaid(true);
                    b.setPaidAt(LocalDateTime.now());
                }
            }
            bookingRepository.saveAll(weekCustomerBookings);

            // Snapshot amount (all current, non-canceled bookings)
            BigDecimal amountAfter = weekCustomerBookings.stream()
                    .map(pricingService::priceFor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            invoice.setAmount(amountAfter);
            invoice.setPaid(true);
            invoice.setPaidAt(LocalDateTime.now());
            invoiceRepository.save(invoice);

            ra.addFlashAttribute("invoiceMessage", "Invoice marked paid. Week finalized and all bookings marked paid.");
        } else {
            // Invoice already paid: pay only the NEW (unpaid) bookings in this week
            List<Booking> unpaid = weekCustomerBookings.stream()
                    .filter(b -> !b.isPaid())
                    .collect(Collectors.toList());

            if (unpaid.isEmpty()) {
                ra.addFlashAttribute("invoiceMessage", "No new unpaid bookings to apply payment to.");
                return "redirect:/admin#invoicing";
            }

            for (Booking b : unpaid) {
                b.setPaid(true);
                b.setPaidAt(LocalDateTime.now());
            }
            bookingRepository.saveAll(unpaid);

            // Optional: keep invoice.amount as current snapshot of the week
            BigDecimal amountAfter = weekCustomerBookings.stream()
                    .map(pricingService::priceFor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            invoice.setAmount(amountAfter);
            // invoice remains paid; update timestamp to reflect additional payment applied
            invoice.setPaidAt(LocalDateTime.now());
            invoiceRepository.save(invoice);

            ra.addFlashAttribute("invoiceMessage", "Additional bookings marked paid for this week.");
        }

        return "redirect:/admin#invoicing";
    }
}
