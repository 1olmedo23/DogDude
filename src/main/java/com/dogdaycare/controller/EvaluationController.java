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
import org.springframework.web.bind.annotation.RequestParam;

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
            Model model,
            @RequestParam(name = "additionalDogNames", required = false) List<String> additionalDogNames,
            @RequestParam(name = "additionalDogBreeds", required = false) List<String> additionalDogBreeds
    ) throws IOException {

        if (bindingResult.hasErrors()) {
            model.addAttribute("errors", bindingResult.getAllErrors());
            return "evaluation"; // Re-show the form with errors
        }
        if (evaluationRepository.findByEmail(evaluation.getEmail()).isPresent()) {
            model.addAttribute("errors", List.of(new ObjectError("email", "This email has already been used for an evaluation.")));
            return "evaluation";
        }

        // Build extras (cap at 4, ignore blank rows)
        if (additionalDogNames == null) additionalDogNames = List.of();
        if (additionalDogBreeds == null) additionalDogBreeds = List.of();
        int n = Math.min(Math.min(additionalDogNames.size(), additionalDogBreeds.size()), 4);

        List<EvaluationRequest.AdditionalDog> extras = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String nm = additionalDogNames.get(i);
            String br = additionalDogBreeds.get(i);
            if ((nm != null && !nm.isBlank()) || (br != null && !br.isBlank())) {
                extras.add(new EvaluationRequest.AdditionalDog(
                        nm == null ? "" : nm.trim(),
                        br == null ? "" : br.trim()
                ));
            }
        }
        evaluation.setAdditionalDogs(extras); // attach before save

        try {
            // timestamp
            evaluation.setCreatedAt(java.time.LocalDateTime.now());

            // Save to DB (entity hooks serialize extras into JSON column)
            evaluationRepository.save(evaluation);

            // Save files to disk (up to 5)
            List<File> savedFiles = new ArrayList<>();
            final int MAX_FILES = 5;
            if (files != null && files.length > 0) {
                File dir = new File(uploadDir);
                if (!dir.exists()) dir.mkdirs();

                int count = 0;
                for (MultipartFile file : files) {
                    if (file != null && !file.isEmpty()) {
                        if (count >= MAX_FILES) break;
                        File savedFile = new File(dir, System.currentTimeMillis() + "_" + file.getOriginalFilename());
                        file.transferTo(savedFile);
                        savedFiles.add(savedFile);
                        count++;
                    }
                }
            }

            // Compose extra-dogs text for emails
            String extrasText;
            if (extras.isEmpty()) {
                extrasText = "None";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < extras.size(); i++) {
                    var d = extras.get(i);
                    if (i > 0) sb.append("; ");
                    sb.append((d.getName() == null || d.getName().isBlank()) ? "(No name)" : d.getName());
                    sb.append(" (");
                    sb.append((d.getBreed() == null || d.getBreed().isBlank()) ? "Unknown breed" : d.getBreed());
                    sb.append(")");
                }
                extrasText = sb.toString();
            }

            // Send email to business
            String businessMessage = String.format(
                    "New Evaluation Request:\n\nClient: %s\nEmail: %s\nPhone: %s\n"
                            + "Primary Dog: %s (%s)\n"
                            + "Additional Dogs: %s\n\nFiles attached if provided.",
                    evaluation.getClientName(), evaluation.getEmail(), evaluation.getPhone(),
                    evaluation.getDogName(), evaluation.getDogBreed(),
                    extrasText
            );
            emailService.sendEmailWithAttachments(businessEmail, "New Evaluation Request", businessMessage, savedFiles);

            // Send confirmation email to customer
            String customerMessage = String.format(
                    "Hello %s,\n\nThank you for submitting your evaluation for %s (%s). "
                            + "We also recorded the following additional dogs: %s.\n"
                            + "We’ll review your request and contact you within 3-5 business days via email with further instructions.\n\n- Dog Daycare Team",
                    evaluation.getClientName(), evaluation.getDogName(), evaluation.getDogBreed(), extrasText
            );
            emailService.sendEmail(evaluation.getEmail(), "Evaluation Received - Dog Daycare", customerMessage);

            return "evaluation-success";
        } catch (Exception e) {
            throw new RuntimeException("Error processing evaluation: " + e.getMessage());
        }
    }
}
