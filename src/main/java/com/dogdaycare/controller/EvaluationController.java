package com.dogdaycare.controller;

import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Controller
public class EvaluationController {

    private final EvaluationRepository evaluationRepository;
    private final EmailService emailService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${business.email}")
    private String businessEmail;  // <-- Configurable business email

    public EvaluationController(EvaluationRepository evaluationRepository, EmailService emailService) {
        this.evaluationRepository = evaluationRepository;
        this.emailService = emailService;
    }

    @GetMapping("/evaluation")
    public String showEvaluationForm(EvaluationRequest evaluationRequest) {
        return "evaluation";
    }

    @PostMapping("/evaluation")
    public String submitEvaluation(
            @Valid EvaluationRequest evaluation,
            BindingResult bindingResult,
            MultipartFile[] files,
            Model model
    ) throws IOException {
        if (bindingResult.hasErrors()) {
            model.addAttribute("errors", bindingResult.getAllErrors());
            return "evaluation"; // Re-show the form with errors
        }
        if (evaluationRepository.findByEmail(evaluation.getEmail()).isPresent()) {
            model.addAttribute("errors", List.of(new ObjectError("email", "This email has already been used for an evaluation.")));
            return "evaluation";
        }

        try {
            // Set timestamp
            evaluation.setCreatedAt(java.time.LocalDateTime.now());
            // Save to DB
            evaluationRepository.save(evaluation);

            // Save files to disk
            List<File> savedFiles = new ArrayList<>();
            if (files != null && files.length > 0) {
                File dir = new File(uploadDir);
                if (!dir.exists()) dir.mkdirs();

                int count = 0;
                for (MultipartFile file : files) {
                    if (!file.isEmpty() && count < 3) {
                        File savedFile = new File(dir, System.currentTimeMillis() + "_" + file.getOriginalFilename());
                        file.transferTo(savedFile);
                        savedFiles.add(savedFile);
                        count++;
                    }
                }
            }

            // Send email to business
            String businessMessage = String.format(
                    "New Evaluation Request:\n\nClient: %s\nEmail: %s\nPhone: %s\nDog: %s (%s)\n\nFiles attached if provided.",
                    evaluation.getClientName(), evaluation.getEmail(), evaluation.getPhone(),
                    evaluation.getDogName(), evaluation.getDogBreed()
            );
            emailService.sendEmailWithAttachments(businessEmail, "New Evaluation Request", businessMessage, savedFiles);

            // Send confirmation email to customer
            String customerMessage = String.format(
                    "Hello %s,\n\nThank you for submitting your evaluation for %s (%s). " +
                            "We’ll review your request and contact you within 3-5 business days via email with further instructions.\n\n- Dog Daycare Team",
                    evaluation.getClientName(), evaluation.getDogName(), evaluation.getDogBreed()
            );
            emailService.sendEmail(evaluation.getEmail(), "Evaluation Received - Dog Daycare", customerMessage);

            return "evaluation-success";
        } catch (Exception e) {
            throw new RuntimeException("Error processing evaluation: " + e.getMessage());
        }
    }
}