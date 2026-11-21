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
import com.dogdaycare.service.PricingRules;
import com.dogdaycare.service.PricingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
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
            String dog = evalOpt.map(EvaluationRequest::getDogName).orElse("N/A");

            BigDecimal currentAmount = bookings.stream()
                    .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                    .map(this::finalAmountForInvoice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal paidToDate = bookings.stream()
                    .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                    .filter(Booking::isPaid)
                    .map(this::finalAmountForInvoice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal deltaUnpaid = currentAmount.subtract(paidToDate);
            if (deltaUnpaid.signum() < 0) deltaUnpaid = BigDecimal.ZERO;

            BigDecimal currentAmount2 = currentAmount.setScale(2, RoundingMode.HALF_UP);
            BigDecimal paidToDate2 = paidToDate.setScale(2, RoundingMode.HALF_UP);
            BigDecimal deltaUnpaid2 = deltaUnpaid.setScale(2, RoundingMode.HALF_UP);

            var invOpt = invoiceRepository.findByCustomerEmailAndWeekStart(email, ws);
            boolean invoicePaid = invOpt.map(Invoice::isPaid).orElse(false);

            boolean allDaysPaid = !bookings.isEmpty() && bookings.stream().allMatch(Booking::isPaid);

            Long invoiceId = invOpt.map(Invoice::getId).orElse(null);
            boolean rowPaid = invoicePaid && allDaysPaid;

            rows.add(new InvoiceRowDto(
                    invoiceId,
                    name,
                    email,
                    dog,
                    currentAmount2,
                    rowPaid,
                    paidToDate2,
                    deltaUnpaid2,
                    invoicePaid
            ));
        }

        rows.sort(Comparator.comparing(InvoiceRowDto::getCustomerName, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /**
     * Freeze the current base TOTAL before payment.
     *
     * Does not modify bookings already marked paid.
     * Manual adjustments remain stored separately.
     */
    private void finalizeUnpaidBooking(Booking booking) {
        if (booking == null || booking.isPaid()) {
            return;
        }

        BigDecimal finalBase =
                pricingService.baseTotalFor(booking);

        // Store the full booking total, not a per-dog rate.
        booking.setQuotedRateAtLock(finalBase);

        boolean prepaidDaycare =
                PricingRules.isStandardDaycare(
                        booking.getServiceType())
                        && booking.isAdvanceEligible()
                        && booking.isWantsAdvancePay();

        booking.setInPrepayBundle(prepaidDaycare);

        if (prepaidDaycare) {
            booking.setBundleLockedAt(
                    OffsetDateTime.now(clock)
            );
        }
    }

    @PostMapping("/mark-paid")
    @Transactional
    public String markPaid(
            @RequestParam(value = "invoiceId", required = false) Long invoiceId,
            @RequestParam(value = "email", required = false) String customerEmail,
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            RedirectAttributes ra
    ) {
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

        if (invoice == null) {
            invoice = invoiceRepository.findByCustomerEmailAndWeekStart(customerEmail, ws).orElse(null);
        }

        if (invoice == null) {
            var evalOpt = evaluationRepository.findTopByEmailOrderByCreatedAtDesc(customerEmail);
            String name = evalOpt.map(EvaluationRequest::getClientName).orElse(customerEmail);
            String dog = evalOpt.map(EvaluationRequest::getDogName).orElse("N/A");

            invoice = new Invoice();
            invoice.setCustomerEmail(customerEmail);
            invoice.setCustomerName(name);
            invoice.setDogName(dog);
            invoice.setWeekStart(ws);
            invoice.setWeekEnd(we);
        }

        final String emailKey = customerEmail;
        List<Booking> weekCustomerBookings = bookingRepository.findByDateBetween(ws, we).stream()
                .filter(b -> b.getCustomer() != null && emailKey.equals(b.getCustomer().getUsername()))
                .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                .collect(Collectors.toList());

        if (!invoice.isPaid()) {
            for (Booking b : weekCustomerBookings) {
                if (!b.isPaid()) {

                    // Freeze live pricing before paid=true.
                    finalizeUnpaidBooking(b);

                    b.setPaid(true);
                    b.setPaidAt(LocalDateTime.now(clock));
                }
            }
            bookingRepository.saveAll(weekCustomerBookings);

            BigDecimal amountAfter = weekCustomerBookings.stream()
                    .map(this::finalAmountForInvoice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            invoice.setAmount(amountAfter);
            invoice.setPaid(true);
            invoice.setPaidAt(LocalDateTime.now(clock));
            invoiceRepository.save(invoice);

            ra.addFlashAttribute("invoiceMessage", "Invoice marked paid. Week finalized and all bookings marked paid.");
        } else {
            List<Booking> unpaid = weekCustomerBookings.stream()
                    .filter(b -> !b.isPaid())
                    .collect(Collectors.toList());

            if (unpaid.isEmpty()) {
                ra.addFlashAttribute("invoiceMessage", "No new unpaid bookings to apply payment to.");
                return "redirect:/admin?invoiceWeek=" + ws + "#invoicing";
            }

            for (Booking b : unpaid) {

                // Recalculate only newly unpaid bookings.
                // Previously paid amounts remain untouched.
                finalizeUnpaidBooking(b);

                b.setPaid(true);
                b.setPaidAt(LocalDateTime.now(clock));
            }
            bookingRepository.saveAll(unpaid);

            BigDecimal amountAfter = weekCustomerBookings.stream()
                    .map(this::finalAmountForInvoice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            invoice.setAmount(amountAfter);
            invoice.setPaidAt(LocalDateTime.now(clock));
            invoiceRepository.save(invoice);

            ra.addFlashAttribute("invoiceMessage", "Additional bookings marked paid for this week.");
        }

        return "redirect:/admin?invoiceWeek=" + ws + "#invoicing";
    }

    @PostMapping("/revert-paid")
    @Transactional
    public String revertPaid(
            @RequestParam("invoiceId") Long invoiceId,
            RedirectAttributes ra
    ) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);

        if (invoice == null) {
            ra.addFlashAttribute("invoiceMessage", "Invoice not found.");
            return "redirect:/admin#invoicing";
        }

        String customerEmail = invoice.getCustomerEmail();
        LocalDate ws = invoice.getWeekStart();
        LocalDate we = invoice.getWeekEnd();

        List<Booking> weekCustomerBookings = bookingRepository.findByDateBetween(ws, we).stream()
                .filter(b -> b.getCustomer() != null)
                .filter(b -> customerEmail.equals(b.getCustomer().getUsername()))
                .filter(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()))
                .collect(Collectors.toList());

        for (Booking b : weekCustomerBookings) {
            b.setPaid(false);
            b.setPaidAt(null);
        }

        bookingRepository.saveAll(weekCustomerBookings);

        invoice.setPaid(false);
        invoice.setPaidAt(null);
        invoiceRepository.save(invoice);

        ra.addFlashAttribute("invoiceMessage", "Invoice reverted to unpaid.");
        return "redirect:/admin?invoiceWeek=" + ws + "#invoicing";
    }

    /**
     * Returns the current invoice total for one booking,
     * including the manual adjustment exactly once.
     *
     * Unpaid bookings use live pricing.
     * Paid bookings use their saved finalized base total.
     */
    private BigDecimal finalAmountForInvoice(Booking booking) {
        return pricingService.finalTotalFor(booking);
    }

    /**
     * Returns the base booking total before adjustments.
     *
     * quotedRateAtLock is treated as a booking TOTAL,
     * not a per-dog rate.
     */
    private BigDecimal baseAmountForInvoice(Booking booking) {
        return pricingService.baseTotalFor(booking);
    }
}