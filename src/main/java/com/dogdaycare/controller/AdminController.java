package com.dogdaycare.controller;

import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.EmailService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.List;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final EvaluationRepository evaluationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public AdminController(EvaluationRepository evaluationRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService) {
        this.evaluationRepository = evaluationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    // Admin dashboard
    @GetMapping
    public String adminDashboard(Model model) {
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);

        List<EvaluationRequest> evaluations = evaluationRepository.findAll()
                .stream()
                .filter(e -> !e.isApproved() || e.getCreatedAt().isAfter(threeDaysAgo))
                .collect(Collectors.toList());

        List<User> users = userRepository.findAll()
                .stream()
                .sorted((u1, u2) -> u1.getId().compareTo(u2.getId()))
                .collect(Collectors.toList());

        model.addAttribute("evaluations", evaluations);
        model.addAttribute("users", users);
        return "admin";
    }

    // Approve an evaluation and create a user with a custom password
    @PostMapping("/approve/{id}")
    public String approveEvaluation(@PathVariable Long id, @RequestParam String password) {
        EvaluationRequest evaluation = evaluationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Evaluation not found"));

        // Only create a login if the evaluation is not yet approved
        if (!evaluation.isApproved()) {
            evaluation.setApproved(true);
            evaluationRepository.save(evaluation);

            // Create a new user
            User newUser = new User();
            newUser.setUsername(evaluation.getEmail());
            newUser.setPassword(passwordEncoder.encode(password));
            newUser.setRole("CUSTOMER");
            newUser.setEnabled(true);
            userRepository.save(newUser);

            // Send polished approval email with credentials
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