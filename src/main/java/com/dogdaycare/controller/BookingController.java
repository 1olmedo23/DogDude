package com.dogdaycare.controller;

import com.dogdaycare.model.Booking;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.BookingRepository;
import com.dogdaycare.repository.FileRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.BookingLimitService;
import com.dogdaycare.service.BundleService;
import com.dogdaycare.service.CancelPolicyService;
import com.dogdaycare.service.PricingService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.format.annotation.DateTimeFormat;
import static org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/booking")
public class BookingController {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final BookingLimitService bookingLimitService;
    private final CancelPolicyService cancelPolicyService;
    private final FileRepository fileRepository;
    private final PricingService pricingService;
    private final BundleService bundleService;

    private final Clock clock;

    public BookingController(BookingRepository bookingRepository,
                             UserRepository userRepository,
                             BookingLimitService bookingLimitService,
                             CancelPolicyService cancelPolicyService,
                             FileRepository fileRepository,
                             PricingService pricingService,
                             BundleService bundleService,
                             Clock clock) {
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.bookingLimitService = bookingLimitService;
        this.cancelPolicyService = cancelPolicyService;
        this.fileRepository = fileRepository;
        this.pricingService = pricingService;
        this.bundleService = bundleService;
        this.clock = clock;
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private void prepareBookingPage(User customer,
                                    Model model,
                                    String successMessage,
                                    String errorMessage) {

        var all = bookingRepository.findByCustomer(customer);
        // Show only CURRENT week (Mon–Sun) and future on the customer page
        LocalDate visibleFrom = pricingService.weekStartMonday(LocalDate.now(clock));
        var allVisible = all.stream()
                .filter(b -> b.getDate() == null || !b.getDate().isBefore(visibleFrom))
                .collect(Collectors.toList());

// Build a date -> service label map for non-canceled bookings in that window
        Map<LocalDate, String> bookedByDate = allVisible.stream()
                .filter(b -> b.getDate() != null)
                .filter(b -> {
                    Object st = b.getStatus();
                    return st == null || !"CANCELED".equalsIgnoreCase(String.valueOf(st));
                })
                .collect(Collectors.groupingBy(
                        Booking::getDate,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                b -> safe(b.getServiceType()),
                                Collectors.toCollection(LinkedHashSet::new)
                        )
                ))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> {
                            var services = e.getValue().stream()
                                    .filter(s -> s != null && !s.isBlank())
                                    .toList();
                            if (services.isEmpty()) return "Booked";
                            if (services.size() == 1) return services.get(0);
                            return "Multiple";
                        },
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        model.addAttribute("bookedByDate", bookedByDate);

        Comparator<Booking> sortByDateThenTime =
                Comparator.comparing(Booking::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Booking::getTime, Comparator.nullsLast(Comparator.naturalOrder()));

        var daycare = allVisible.stream()
                .filter(b -> b.getServiceType() != null && b.getServiceType().toLowerCase().contains("daycare"))
                .sorted(sortByDateThenTime)
                .toList();

        var boarding = allVisible.stream()
                .filter(b -> b.getServiceType() != null && b.getServiceType().toLowerCase().contains("boarding"))
                .sorted(sortByDateThenTime)
                .toList();

        var daycareShort = daycare.stream()
                .filter(b -> "Daycare (6 AM - 3 PM)".equalsIgnoreCase(safe(b.getServiceType())))
                .toList();

        var daycareLong = daycare.stream()
                .filter(b -> "Daycare (6 AM - 8 PM)".equalsIgnoreCase(safe(b.getServiceType())))
                .toList();

        var daycareAfterHours = daycare.stream()
                .filter(b -> "Daycare After Hours (6 AM - 11 PM)".equalsIgnoreCase(safe(b.getServiceType())))
                .toList();

        var files = fileRepository.findByUserIdOrderByCreatedAtDesc(customer.getId());
        model.addAttribute("files", files);

        model.addAttribute("bookings", allVisible);
        model.addAttribute("bookingsDaycare", daycare);
        model.addAttribute("bookingsBoarding", boarding);
        model.addAttribute("bookingsDaycareShort", daycareShort);
        model.addAttribute("bookingsDaycareLong", daycareLong);
        model.addAttribute("bookingsDaycareAfterHours", daycareAfterHours);
        model.addAttribute("today", LocalDate.now(clock));

        model.addAttribute("services", List.of(
                "Daycare (6 AM - 3 PM)",
                "Daycare (6 AM - 8 PM)",
                "Daycare After Hours (6 AM - 11 PM)",
                "Boarding"
        ));
        model.addAttribute("activePage", "booking");

        if (successMessage != null && !successMessage.isBlank()) {
            model.addAttribute("successMessage", successMessage);
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            model.addAttribute("errorMessage", errorMessage);
        }

        // Permanent weekly banner flags (THIS week: Mon–Sun of today)
        LocalDate selected = LocalDate.now(clock);
        boolean hasWeekPaid = bundleService.hasWeekPaid(customer, selected);
        model.addAttribute("hasWeekPaidThisWeek", hasWeekPaid);
        model.addAttribute("weekStart", pricingService.weekStartMonday(selected));
        model.addAttribute("weekEnd", pricingService.weekEndSunday(selected));

        // === Provisional daycare quotes (always dynamic; no locking) ===
        // For each Mon–Sun week, compute provisional quotes for ALL eligible, opted-in daycare bookings.
        // We INCLUDE paid bookings here so customers still see prices after payment.
        Map<Long, BigDecimal> provisionalQuotes = new HashMap<>();
        var byWeek = daycare.stream()
                .filter(b -> {
                    Object st = b.getStatus();
                    return st == null || !"CANCELED".equalsIgnoreCase(String.valueOf(st));
                })
                .collect(Collectors.groupingBy(b -> pricingService.weekStartMonday(b.getDate())));

        for (var entry : byWeek.entrySet()) {
            var weekBookings = entry.getValue();

            // Eligible daycare for the week (opted-in + advance-eligible)
            var eligible = weekBookings.stream()
                    .filter(b -> b.isWantsAdvancePay() && b.isAdvanceEligible())
                    .toList();

            if (eligible.isEmpty()) continue;

            boolean atLeast4 = eligible.size() >= 4;

            // Quote every eligible daycare in that week (paid or not)
            for (var b : eligible) {
                java.math.BigDecimal base = pricingService.quoteDaycareAtTier(b, atLeast4);
                int n = (b.getDogCount() != null ? b.getDogCount() : 1);
                provisionalQuotes.put(b.getId(), base.multiply(java.math.BigDecimal.valueOf(n)));
            }
        }
        model.addAttribute("provisionalQuotes", provisionalQuotes);
    }

    @GetMapping("/week-paid")
    @ResponseBody
    public Map<String, Boolean> isWeekPaid(
            @RequestParam("date") @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            Authentication authentication
    ) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();
        boolean weekPaid = bundleService.hasWeekPaid(customer, pricingService.weekStartMonday(date));
        return Map.of("weekPaid", weekPaid);
    }

    @GetMapping
    public String bookingPage(Authentication authentication, Model model,
                              @RequestParam(value = "start", required = false) String startIso,
                              @ModelAttribute("successMessage") String successMessage,
                              @ModelAttribute("errorMessage") String errorMessage) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        // --- Base grouping you already show (bookings lists, files, etc.) ---
        prepareBookingPage(customer, model,
                (successMessage != null && !successMessage.isBlank()) ? successMessage : null,
                (errorMessage != null && !errorMessage.isBlank()) ? errorMessage : null);

        // === Two-week calendar support ===
        LocalDate today = LocalDate.now(clock);
        LocalDate anchor = (startIso != null && !startIso.isBlank())
                ? LocalDate.parse(startIso)
                : today;
        LocalDate week1Monday = pricingService.weekStartMonday(anchor);
        LocalDate week2Monday = week1Monday.plusWeeks(1);

        // Build day lists (Mon..Sun)
        List<LocalDate> week1Days = weekDays(week1Monday);
        List<LocalDate> week2Days = weekDays(week2Monday);

        // Week banners: “planned/paid?” by week
        boolean week1Paid = bundleService.hasWeekPaid(customer, week1Monday);
        boolean week2Paid = bundleService.hasWeekPaid(customer, week2Monday);

        // Drop-off times (adjust to your exact slots)
        List<String> dropoffTimes = List.of("06:00", "06:30", "07:00", "07:30", "08:00", "08:30",
                "09:00", "09:30", "10:00", "11:00", "12:00");

        // Nav anchors (two-week paging)
        model.addAttribute("week1Monday", week1Monday);
        model.addAttribute("week2Monday", week2Monday);
        model.addAttribute("week1Days", week1Days);
        model.addAttribute("week2Days", week2Days);
        model.addAttribute("week1Paid", week1Paid);
        model.addAttribute("week2Paid", week2Paid);
        model.addAttribute("prevStart", week1Monday.minusWeeks(2));
        model.addAttribute("nextStart", week1Monday.plusWeeks(2));
        model.addAttribute("dropoffTimes", dropoffTimes);

        // Keep this for banner logic on the current week (already used in your JS)
        model.addAttribute("hasWeekPaidThisWeek", bundleService.hasWeekPaid(customer, LocalDate.now(clock)));

        return "booking";
    }

    private List<LocalDate> weekDays(LocalDate monday) {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(monday::plusDays)
                .toList();
    }

    @GetMapping("/quote")
    @ResponseBody
    public Map<String, Object> quote(
            Authentication authentication,
            @RequestParam String serviceType,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            @RequestParam(required = false) String time,
            @RequestParam(name = "wantsAdvancePay", defaultValue = "false") boolean wantsAdvancePay,
            @RequestParam(name = "dogCount", defaultValue = "1") Integer dogCount // <-- NEW
    ) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        // Clamp dogCount to [1..5]
        if (dogCount == null) dogCount = 1;
        dogCount = Math.max(1, Math.min(5, dogCount));

        // Parse time (optional)
        LocalTime localTime = null;
        if (time != null && !time.isBlank()) {
            localTime = LocalTime.parse(time);
        }

        // Build a dummy booking shell for shared helpers
        Booking probe = new Booking();
        probe.setCustomer(customer);
        probe.setServiceType(serviceType);
        probe.setDate(date);
        probe.setTime(localTime);

        boolean isDaycare = serviceType != null && serviceType.toLowerCase().contains("daycare");
        boolean isBoarding = serviceType != null && serviceType.toLowerCase().contains("boarding");
        boolean isAfterHours = serviceType != null && serviceType.toLowerCase().contains("after hours");

        boolean advanceEligible = false;
        if (isDaycare && localTime != null) {
            var now = ZonedDateTime.now(clock);
            var zone = clock.getZone();
            var zdt = ZonedDateTime.of(date, localTime, zone);
            long hours = Duration.between(now, zdt).toHours();
            advanceEligible = hours >= 24;
        }

        if (isAfterHours) {
            // Flat 90 per *booking*. Multiply by dogs for preview.
            var base = new java.math.BigDecimal("90.00").setScale(2, java.math.RoundingMode.HALF_UP);
            var total = base.multiply(java.math.BigDecimal.valueOf(dogCount));
            return Map.of(
                    "amount", total.toString(),
                    "currency", "USD",
                    "advanceEligible", false,
                    "wantsAdvancePayApplied", false,
                    "weekAlreadyPaid", false,
                    "note", "Daycare After Hours flat rate × " + dogCount
            );
        }

        boolean weekAlreadyPaid = bundleService.hasWeekPaid(customer, pricingService.weekStartMonday(date));
        boolean wantsAdvancePayFinal = isDaycare && advanceEligible && wantsAdvancePay && !weekAlreadyPaid;

        java.math.BigDecimal amount;
        String note;

        if (isDaycare) {
            amount = pricingService.previewDaycarePrice(
                    customer, date, serviceType, advanceEligible, wantsAdvancePayFinal
            );
            note = wantsAdvancePayFinal
                    ? "Daycare prepay preview × " + dogCount
                    : (advanceEligible ? "Daycare immediate preview × " + dogCount : "Daycare (not prepay-eligible) × " + dogCount);
        } else if (isBoarding) {
            amount = pricingService.priceFor(probe);
            note = "Boarding preview × " + dogCount + " (prior-month tier & last-night logic).";
        } else {
            amount = java.math.BigDecimal.ZERO;
            note = "Unknown service.";
        }

        // Multiply by dogCount for the preview
        var total = amount.multiply(java.math.BigDecimal.valueOf(dogCount));

        return Map.of(
                "amount", total.setScale(2, java.math.RoundingMode.HALF_UP).toString(),
                "currency", "USD",
                "advanceEligible", advanceEligible,
                "wantsAdvancePayApplied", wantsAdvancePayFinal,
                "weekAlreadyPaid", weekAlreadyPaid,
                "note", note
        );
    }

    @PostMapping
    public String createBooking(Authentication authentication,
                                @RequestParam String serviceType,
                                @RequestParam String date,
                                @RequestParam String time,
                                @RequestParam(name = "dogCount", defaultValue = "1") Integer dogCount,
                                @RequestParam(name = "wantsAdvancePay", required = false, defaultValue = "false") boolean wantsAdvancePay,
                                RedirectAttributes redirectAttributes) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();
        if (dogCount == null) dogCount = 1;
        dogCount = Math.max(1, Math.min(5, dogCount));

        LocalDate localDate = LocalDate.parse(date);
        LocalTime localTime = LocalTime.parse(time);

        // Prevent double-booking on the same calendar day (any service)
        var sameDay = bookingRepository.findByCustomerAndDate(customer, localDate);
        boolean hasAnyServiceSameDay = sameDay.stream()
                .anyMatch(b -> !"CANCELED".equalsIgnoreCase(b.getStatus()));

        if (hasAnyServiceSameDay) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "You have already booked a service for this day."
            );
            return "redirect:/booking";
        }

        // Capacity check
        boolean canBook = bookingLimitService.canCustomerBook(localDate, serviceType);
        if (!canBook) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "We’re full for this day. Please try a different date. " +
                            "If this is an emergency, please contact the business at (XXX) XXX-XXXX."
            );
            return "redirect:/booking";
        }

        // Compute advance eligibility (24h rule for daycare only)
        boolean isDaycareFlag = serviceType != null && serviceType.toLowerCase().contains("daycare");
        boolean isAfterHours = serviceType != null && serviceType.toLowerCase().contains("after hours");
        boolean advanceEligible = false;
        if (isDaycareFlag) {
            var now = ZonedDateTime.now(clock);
            var zone = clock.getZone();
            var bookingZdt = ZonedDateTime.of(localDate, localTime, zone);
            long hours = Duration.between(now, bookingZdt).toHours();
            advanceEligible = hours >= 24;
        }
        // Block prepay if this customer's week is already paid
        LocalDate weekStart = pricingService.weekStartMonday(localDate);
        boolean weekAlreadyPaid = bundleService.hasWeekPaid(customer, weekStart);

        // Final flag we persist:
        boolean wantsAdvancePayFinal =
                !isAfterHours && isDaycareFlag && advanceEligible && wantsAdvancePay && !weekAlreadyPaid; // NEW: block for After Hours

        // Build & persist
        Booking booking = new Booking();
        booking.setCustomer(customer);
        booking.setServiceType(serviceType);
        booking.setDate(localDate);
        booking.setTime(localTime);
        booking.setStatus("APPROVED");

        booking.setDogCount(dogCount); // <-- NEW

        booking.setCreatedAt(LocalDateTime.now(clock));
        booking.setAdvanceEligible(advanceEligible);
        booking.setWantsAdvancePay(wantsAdvancePayFinal);

        // --- Price lock (per-dog × dogCount) ---
        java.math.BigDecimal base;
        if (isAfterHours) {
            base = new java.math.BigDecimal("90.00"); // flat per dog
        } else if (isDaycareFlag) {
            // Use the same logic in quote(): prepay vs immediate
            base = pricingService.previewDaycarePrice(
                    customer,
                    localDate,
                    serviceType,
                    advanceEligible,
                    wantsAdvancePayFinal
            );
        } else if (serviceType != null && serviceType.toLowerCase().contains("boarding")) {
            // priceFor(Booking) already contains boarding rules (tier, last night, etc.)
            Booking probe = new Booking();
            probe.setCustomer(customer);
            probe.setServiceType(serviceType);
            probe.setDate(localDate);
            probe.setTime(localTime);
            base = pricingService.priceFor(probe);
        } else {
            base = java.math.BigDecimal.ZERO;
        }

// Multiply by # of dogs and store on the booking
        java.math.BigDecimal total = base.multiply(java.math.BigDecimal.valueOf(dogCount))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        booking.setQuotedRateAtLock(total);

        bookingRepository.save(booking);

        String msg = "Booking submitted successfully!";
        if (wantsAdvancePayFinal) {
            msg += " We’ll contact you to process the advance payment at the discounted rate.";
        }
        redirectAttributes.addFlashAttribute("successMessage", msg);
        return "redirect:/booking";
    }

    @PostMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id,
                                Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        Booking booking = bookingRepository.findById(id).orElse(null);
        if (booking != null && booking.getCustomer() != null
                && booking.getCustomer().getUsername().equals(authentication.getName())) {

            // Enforce 72-hour rule for Boarding (customers only)
            boolean canCancel = cancelPolicyService.canCustomerCancel(booking, clock);
            if (!canCancel) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        cancelPolicyService.policyMessage(booking)
                );
                return "redirect:/booking";
            }

            booking.setStatus("CANCELED");
            bookingRepository.save(booking);
            redirectAttributes.addFlashAttribute("successMessage", "Your booking has been canceled.");
        }
        return "redirect:/booking";
    }
}
