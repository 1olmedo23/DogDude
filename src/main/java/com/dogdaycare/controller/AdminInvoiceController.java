package com.dogdaycare.controller;

import com.dogdaycare.dto.InvoiceRowDto;
import com.dogdaycare.model.Booking;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.Invoice;
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
import java.math.RoundingMode;

import java.math.BigDecimal;
import java.time.Clock;
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
    private final Clock clock;

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
                                  BundleService bundleService,
                                  Clock clock) {
        this.bookingRepository = bookingRepository;
        this.evaluationRepository = evaluationRepository;
        this.invoiceRepository = invoiceRepository;
        this.pricingService = pricingService;
        this.userRepository = userRepository;
        this.weeklyRepo = weeklyRepo;
        this.bundleService = bundleService;
        this.clock = clock;
    }

    private LocalDate weekStart(LocalDate any) { return any.with(DayOfWeek.MONDAY); }
    private LocalDate weekEnd(LocalDate start) { return start.plusDays(6); }
    private LocalDate lastCompletedWeekStart() {
        LocalDate today = LocalDate.now(clock);
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

            // Determine week-tier for this customer (count daycare bookings in the week, non-canceled)
            boolean atLeast4 = bookings.stream()
                    .filter(b -> b.getServiceType() != null && b.getServiceType().toLowerCase().contains("daycare"))
                    .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                    .count() >= 4;

            BigDecimal currentAmount = bookings.stream()
                    .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                    .map(b -> {
                        int n = (b.getDogCount() != null ? b.getDogCount() : 1);
                        String svc = (b.getServiceType() == null ? "" : b.getServiceType()).toLowerCase();

                        if (svc.contains("after hours")) {
                            return new BigDecimal("90.00").multiply(BigDecimal.valueOf(n));
                        } else if (svc.contains("boarding")) {
                            return pricingService.priceFor(b).multiply(BigDecimal.valueOf(n));
                        } else if (svc.contains("daycare")) {
                            BigDecimal perDog = pricingService.quoteDaycareAtTier(b, atLeast4);
                            return perDog.multiply(BigDecimal.valueOf(n));
                        } else {
                            return pricingService.priceFor(b).multiply(BigDecimal.valueOf(n));
                        }
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal paidToDate = bookings.stream()
                    .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                    .filter(Booking::isPaid)
                    .map(b -> {
                        int n = (b.getDogCount() != null ? b.getDogCount() : 1);
                        String svc = (b.getServiceType() == null ? "" : b.getServiceType()).toLowerCase();

                        if (svc.contains("after hours")) {
                            return new BigDecimal("90.00").multiply(BigDecimal.valueOf(n));
                        } else if (svc.contains("boarding")) {
                            return pricingService.priceFor(b).multiply(BigDecimal.valueOf(n));
                        } else if (svc.contains("daycare")) {
                            BigDecimal perDog = pricingService.quoteDaycareAtTier(b, atLeast4);
                            return perDog.multiply(BigDecimal.valueOf(n));
                        } else {
                            return pricingService.priceFor(b).multiply(BigDecimal.valueOf(n));
                        }
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal deltaUnpaid = currentAmount.subtract(paidToDate);
            if (deltaUnpaid.signum() < 0) deltaUnpaid = BigDecimal.ZERO;

            BigDecimal currentAmount2 = currentAmount.setScale(2, RoundingMode.HALF_UP);
            BigDecimal paidToDate2    = paidToDate.setScale(2, RoundingMode.HALF_UP);
            BigDecimal deltaUnpaid2   = deltaUnpaid.setScale(2, RoundingMode.HALF_UP);

            // invoice record (may exist)
            var invOpt = invoiceRepository.findByCustomerEmailAndWeekStart(email, ws);
            boolean invoicePaid = invOpt.map(Invoice::isPaid).orElse(false);

            // all non-canceled days paid this week?
            boolean allDaysPaid = !bookings.isEmpty() && bookings.stream().allMatch(Booking::isPaid);

            Long invoiceId = invOpt.map(Invoice::getId).orElse(null);
            boolean rowPaid = invoicePaid && allDaysPaid;

            rows.add(new InvoiceRowDto(
                    invoiceId,
                    name,
                    email,
                    dog,
                    currentAmount2,   // total
                    rowPaid,
                    paidToDate2,      // previouslyPaidAmount (aliased to paidToDate only if your test wants that)
                    deltaUnpaid2,     // newSincePaid
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
        final String emailKey = customerEmail; // effectively final for lambdas
        List<Booking> weekCustomerBookings = bookingRepository.findByDateBetween(ws, we).stream()
                .filter(b -> b.getCustomer() != null && emailKey.equals(b.getCustomer().getUsername()))
                .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                .collect(Collectors.toList());

        if (!invoice.isPaid()) {
            // First-time payment: mark ALL week bookings paid
            for (Booking b : weekCustomerBookings) {
                if (!b.isPaid()) {
                    b.setPaid(true);
                    b.setPaidAt(LocalDateTime.now(clock));
                }
            }
            bookingRepository.saveAll(weekCustomerBookings);

            // Snapshot amount (use locked total if present, else priceFor×dogCount)
            BigDecimal amountAfter = weekCustomerBookings.stream()
                    .map(b -> {
                        BigDecimal locked = b.getQuotedRateAtLock();
                        if (locked != null) return locked;
                        BigDecimal perDog = pricingService.priceFor(b);
                        int n = (b.getDogCount() != null ? b.getDogCount() : 1);
                        return perDog.multiply(BigDecimal.valueOf(n));
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            invoice.setAmount(amountAfter);
            invoice.setPaid(true);
            invoice.setPaidAt(LocalDateTime.now(clock));
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
                b.setPaidAt(LocalDateTime.now(clock));
            }
            bookingRepository.saveAll(unpaid);

            // Optionally keep invoice.amount as current snapshot of the week; locked if available
            BigDecimal amountAfter = weekCustomerBookings.stream()
                    .map(b -> {
                        BigDecimal locked = b.getQuotedRateAtLock();
                        if (locked != null) return locked;
                        BigDecimal perDog = pricingService.priceFor(b);
                        int n = (b.getDogCount() != null ? b.getDogCount() : 1);
                        return perDog.multiply(BigDecimal.valueOf(n));
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // invoice remains paid; update timestamp to reflect additional payment applied
            invoice.setPaidAt(LocalDateTime.now(clock));
            invoiceRepository.save(invoice);

            ra.addFlashAttribute("invoiceMessage", "Additional bookings marked paid for this week.");
        }

        return "redirect:/admin#invoicing";
    }
}
