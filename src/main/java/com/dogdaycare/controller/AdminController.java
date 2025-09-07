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

    public AdminController(EvaluationRepository evaluationRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService,
                           FileRepository fileRepository) {
        this.evaluationRepository = evaluationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.fileRepository = fileRepository;
    }

    // Admin dashboard (now also hydrates the Uploads tab model)
    @GetMapping
    public String adminDashboard(Model model,
                                 @RequestParam(value = "q", required = false) String q,
                                 @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
                                 @RequestParam(value = "openTab", required = false) String openTab) {
        // --- Evaluations (keep recent/pending)
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        List<EvaluationRequest> evaluations = evaluationRepository.findAll()
                .stream()
                .filter(e -> !e.isApproved() || e.getCreatedAt().isAfter(threeDaysAgo))
                .collect(Collectors.toList());

        // --- Users list (sorted) - reused by Uploads tab to label groups
        List<User> allUsersSorted = userRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(User::getId))
                .collect(Collectors.toList());

        // --- Uploads tab data
        List<User> usersForUploads;
        if (q != null && !q.isBlank()) {
            String ql = q.toLowerCase();
            usersForUploads = allUsersSorted.stream()
                    .filter(u -> u.getUsername() != null && u.getUsername().toLowerCase().contains(ql))
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

            String approvalMessage = String.format(
                    "Hello %s,\n\n" +
                            "Your evaluation has been approved! You can now log in to our Dog Daycare booking system:\n\n" +
                            "Login: %s\nPassword: %s\n\n" +
                            "We recommend changing your password after your first login.\n\n" +
                            "Thank you,\nDog Daycare Team",
                    evaluation.getClientName(),
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

    // Toggle a user's enabled/disabled state
    @PostMapping("/toggle/{id}")
    public String toggleUser(@PathVariable Long id) {
        User user = userRepository.findById(id).orElseThrow();
        user.setEnabled(!user.isEnabled());
        userRepository.save(user);
        return "redirect:/admin";
    }
}
