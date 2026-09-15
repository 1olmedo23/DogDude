package com.dogdaycare.controller;

import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.UploadedFile;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.FileRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.EmailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.dogdaycare.service.UploadService;
import java.io.File;
import java.nio.file.Path;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final EvaluationRepository evaluationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final FileRepository fileRepository;
    private final UploadService uploadService;

    public AdminController(EvaluationRepository evaluationRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService,
                           FileRepository fileRepository,
                           UploadService uploadService) {
        this.evaluationRepository = evaluationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.fileRepository = fileRepository;
        this.uploadService = uploadService;
    }

    // Admin dashboard (now also hydrates the Uploads tab model)
    @GetMapping
    public String adminDashboard(Model model,
                                 @RequestParam(value = "q", required = false) String q,
                                 @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
                                 @RequestParam(value = "openTab", required = false) String openTab) {
        // --- Evaluations
        List<EvaluationRequest> allEvaluations = evaluationRepository.findAll();

        List<EvaluationRequest> evaluations = allEvaluations.stream()
                .filter(e -> !e.isApproved())
                .collect(Collectors.toList());

        // --- Users list (sorted) - reused by Uploads tab to label groups
        List<User> allUsersSorted = userRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        // --- Customer account display data
        Map<String, EvaluationRequest> evaluationByEmail = allEvaluations.stream()
                .filter(e -> e.getEmail() != null && !e.getEmail().isBlank())
                .collect(Collectors.toMap(
                        e -> e.getEmail().trim().toLowerCase(Locale.ROOT),
                        e -> e,
                        (first, second) -> {
                            if (first.getCreatedAt() == null) return second;
                            if (second.getCreatedAt() == null) return first;

                            return second.getCreatedAt().isAfter(first.getCreatedAt())
                                    ? second
                                    : first;
                        }
                ));

        Comparator<User> customerDogNameComparator = Comparator.comparing(
                (User user) -> {
                    if (user.getUsername() == null) {
                        return null;
                    }

                    EvaluationRequest evaluation = evaluationByEmail.get(
                            user.getUsername().trim().toLowerCase(Locale.ROOT)
                    );

                    if (evaluation == null
                            || evaluation.getDogName() == null
                            || evaluation.getDogName().isBlank()) {
                        return null;
                    }

                    return evaluation.getDogName().trim();
                },
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
        ).thenComparing(
                User::getUsername,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
        );

        List<User> customerUsers = allUsersSorted.stream()
                .filter(user -> "CUSTOMER".equalsIgnoreCase(user.getRole()))
                .sorted(customerDogNameComparator)
                .collect(Collectors.toList());

        Map<Long, EvaluationRequest> evaluationByUserId = new HashMap<>();

        for (User user : customerUsers) {
            if (user.getUsername() == null) {
                continue;
            }

            EvaluationRequest evaluation = evaluationByEmail.get(
                    user.getUsername().trim().toLowerCase(Locale.ROOT)
            );

            if (evaluation != null) {
                evaluationByUserId.put(user.getId(), evaluation);
            }
        }

        List<User> staffUsers = allUsersSorted.stream()
                .filter(user ->
                        "ADMIN".equalsIgnoreCase(user.getRole())
                                || "EMPLOYEE".equalsIgnoreCase(user.getRole()))
                .sorted(
                        Comparator.comparing(
                                User::getRole,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                        ).thenComparing(
                                User::getUsername,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                        )
                )
                .collect(Collectors.toList());

        // --- Uploads tab data
        List<User> usersForUploads;

        if (q != null && !q.isBlank()) {
            String ql = q.trim().toLowerCase(Locale.ROOT);

            usersForUploads = allUsersSorted.stream()
                    .filter(user -> {

                        // Search by email
                        if (user.getUsername() != null
                                && user.getUsername().toLowerCase(Locale.ROOT).contains(ql)) {
                            return true;
                        }

                        // Find the evaluation information associated with this customer
                        EvaluationRequest evaluation = null;

                        if (user.getUsername() != null) {
                            evaluation = evaluationByEmail.get(
                                    user.getUsername().trim().toLowerCase(Locale.ROOT)
                            );
                        }

                        if (evaluation == null) {
                            return false;
                        }

                        // Search by client name
                        if (evaluation.getClientName() != null
                                && evaluation.getClientName().toLowerCase(Locale.ROOT).contains(ql)) {
                            return true;
                        }

                        // Search by dog name
                        return evaluation.getDogName() != null
                                && evaluation.getDogName().toLowerCase(Locale.ROOT).contains(ql);
                    })
                    .collect(Collectors.toList());

        } else {
            usersForUploads = allUsersSorted;
        }

        List<UploadedFile> files;
        LocalDate today = LocalDate.now();

        switch (filter) {
            case "expired" -> files = fileRepository.findByExpirationDateBeforeOrderByExpirationDateAsc(today);
            case "expiring" -> files = fileRepository.findByExpirationDateBetweenOrderByExpirationDateAsc(today, today.plusDays(30));
            default -> {
                List<Long> ids = usersForUploads.stream().map(User::getId).toList();
                files = ids.isEmpty()
                        ? Collections.emptyList()
                        : fileRepository.findByUserIdInOrderByUserIdAscCreatedAtDesc(ids);
            }
        }

        Map<Long, List<UploadedFile>> byUser = new LinkedHashMap<>();
        for (UploadedFile f : files) {
            if (f.getUser() == null) continue;
            byUser.computeIfAbsent(f.getUser().getId(), k -> new ArrayList<>()).add(f);
        }

        // --- Model
        model.addAttribute("evaluations", evaluations);
        model.addAttribute("users", allUsersSorted);

        model.addAttribute("customerUsers", customerUsers);
        model.addAttribute("evaluationByUserId", evaluationByUserId);
        model.addAttribute("staffUsers", staffUsers);

        // Uploads tab attrs
        model.addAttribute("q", q);
        model.addAttribute("filter", filter);
        model.addAttribute("byUser", byUser);

        // if you submitted the filter form, keep the Uploads tab open
        if ("uploads".equalsIgnoreCase(openTab)) {
            model.addAttribute("adminOpenTab", "uploads");
        }

        return "admin";
    }

    // Approve an evaluation and create a user with a custom password
    @PostMapping("/approve/{id}")
    public String approveEvaluation(@PathVariable Long id, @RequestParam String password) {
        EvaluationRequest evaluation = evaluationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Evaluation not found"));

        if (!evaluation.isApproved()) {
            evaluation.setApproved(true);
            evaluationRepository.save(evaluation);

            User newUser = new User();
            newUser.setUsername(evaluation.getEmail());
            newUser.setPassword(passwordEncoder.encode(password));
            newUser.setRole("CUSTOMER");
            newUser.setEnabled(true);
            userRepository.save(newUser);

            String loginUrl = "https://www.fremontdogplaza.com/login";

            String approvalMessage = String.format(
                    "Hello %s,\n\n" +
                            "Your evaluation has been approved! You can now log in to our Dog Daycare booking system:\n\n" +
                            "Login page: %s\n" +
                            "Username: %s\nPassword: %s\n\n" +
                            "Thank you,\nDog Daycare Team",
                    evaluation.getClientName(),
                    loginUrl,
                    evaluation.getEmail(),
                    password
            );

            emailService.sendEmail(
                    evaluation.getEmail(),
                    "Your Dog Daycare Account Has Been Approved",
                    approvalMessage
            );
        }
        return "redirect:/admin";
    }

    @PostMapping("/deny/{id}")
    public String denyEvaluation(@PathVariable Long id, RedirectAttributes ra) {

        EvaluationRequest evaluation = evaluationRepository.findById(id).orElse(null);

        if (evaluation == null) {
            ra.addFlashAttribute("errorMessage", "Evaluation not found.");
            return "redirect:/admin";
        }

        if (evaluation.isApproved()) {
            ra.addFlashAttribute(
                    "errorMessage",
                    "An approved evaluation cannot be denied."
            );
            return "redirect:/admin";
        }

        List<UploadedFile> evaluationFiles =
                fileRepository.findByEvaluationRequestIdOrderByCreatedAtDesc(id);

        for (UploadedFile uploadedFile : evaluationFiles) {
            try {
                Path path = uploadService.resolveDownloadPath(uploadedFile);
                File fileOnDisk = path.toFile();

                if (fileOnDisk.exists()) {
                    fileOnDisk.delete();
                }
            } catch (Exception ignored) {
                // Legacy disk cleanup should not prevent denying the evaluation.
            }

            fileRepository.delete(uploadedFile);
        }

        evaluationRepository.delete(evaluation);

        ra.addFlashAttribute(
                "successMessage",
                "Evaluation denied and removed."
        );

        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/password")
    public String changeCustomerPassword(@PathVariable Long id,
                                         @RequestParam String password,
                                         RedirectAttributes ra) {
        User user = userRepository.findById(id).orElse(null);

        if (user == null) {
            ra.addFlashAttribute("errorMessage", "User not found.");
            return "redirect:/admin";
        }

        if (!"CUSTOMER".equalsIgnoreCase(user.getRole())) {
            ra.addFlashAttribute("errorMessage", "Only customer passwords can be changed here.");
            return "redirect:/admin";
        }

        if (password == null || password.isBlank()) {
            ra.addFlashAttribute("errorMessage", "Password cannot be blank.");
            return "redirect:/admin";
        }

        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user);

        ra.addFlashAttribute("successMessage", "Customer password updated.");
        return "redirect:/admin";
    }

    // Toggle a user's enabled/disabled state
    @PostMapping("/toggle/{id}")
    public String toggleUser(@PathVariable Long id) {
        User user = userRepository.findById(id).orElseThrow();
        user.setEnabled(!user.isEnabled());
        userRepository.save(user);
        return "redirect:/admin";
    }
}
