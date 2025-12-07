package com.dogdaycare.controller;

import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    // DEV-ONLY EMAIL PREVIEW (no DB, no email sent)
    @GetMapping("/dev/eval-email-preview")
    public ResponseEntity<String> previewEvaluationEmail() {
        EvaluationRequest mock = new EvaluationRequest();
        mock.setClientName("Sample Client");
        mock.setDogName("Buddy");
        mock.setDogBreed("Labrador Mix");

        String extrasText = "Bella (Golden Retriever); Max (Beagle)";
        boolean hasExtras = true;

        String html = buildCustomerEmailHtml(mock, extrasText, hasExtras);

        return ResponseEntity
                .ok()
                .header("Content-Type", "text/html; charset=UTF-8")
                .body(html);
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
            model.addAttribute("errors",
                    List.of(new ObjectError("email", "This email has already been used for an evaluation.")));
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
                        File savedFile = new File(dir,
                                System.currentTimeMillis() + "_" + file.getOriginalFilename());
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

            // --------- FRIENDLIER BUSINESS EMAIL (still plain text) ----------
            StringBuilder businessMessageBuilder = new StringBuilder();
            businessMessageBuilder.append("New Evaluation Request\n")
                    .append("-----------------------\n\n")
                    .append("Client name: ").append(evaluation.getClientName()).append("\n")
                    .append("Email: ").append(evaluation.getEmail()).append("\n")
                    .append("Phone: ").append(evaluation.getPhone()).append("\n\n")
                    .append("Primary dog: ").append(evaluation.getDogName())
                    .append(" (").append(evaluation.getDogBreed()).append(")\n")
                    .append("Additional dogs: ").append(extrasText).append("\n\n")
                    .append("Notes:\n")
                    .append("- Submitted via online evaluation form\n")
                    .append("- Vaccine/pet license files attached if provided.\n");

            String businessMessage = businessMessageBuilder.toString();

            emailService.sendEmailWithAttachments(
                    businessEmail,
                    "New Evaluation Request",
                    businessMessage,
                    savedFiles
            );

            // --------- CUSTOMER EMAIL (HTML with picture) ----------
            boolean hasExtras = !extras.isEmpty();
            String customerHtml = buildCustomerEmailHtml(evaluation, extrasText, hasExtras);

            emailService.sendHtmlEmail(
                    evaluation.getEmail(),
                    "We received your evaluation – Dog Daycare",
                    customerHtml
            );

            return "evaluation-success";
        } catch (Exception e) {
            throw new RuntimeException("Error processing evaluation: " + e.getMessage());
        }
    }

    // ----------------- SHARED HTML BUILDER -----------------

    private String buildCustomerEmailHtml(EvaluationRequest evaluation,
                                          String extrasText,
                                          boolean hasExtras) {

        String extrasSentence = hasExtras
                ? "We also recorded the following additional dogs: " + extrasText + "."
                : "(No additional dogs)";

        // Local preview: file under src/main/resources/static/images/email-hero.png
        String imageUrl = "/images/play1.jpg";

        String clientName = evaluation.getClientName() != null ? evaluation.getClientName() : "there";

        String dogName = evaluation.getDogName() != null ? evaluation.getDogName() : "your dog";
        String dogBreed = evaluation.getDogBreed() != null ? evaluation.getDogBreed() : "";

        StringBuilder sb = new StringBuilder();

        sb.append("<!DOCTYPE html>")
                .append("<html>")
                .append("<head>")
                .append("<meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<meta name=\"x-apple-disable-message-reformatting\">")
                .append("</head>")
                .append("<body style=\"margin:0; padding:0; background-color:#f0f1f5; -webkit-text-size-adjust:100%; text-size-adjust:100%;\">")

                // Outer full-width table (background)
                .append("<table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f0f1f5;\">")
                .append("<tr><td align=\"center\" style=\"padding:20px 0;\">")

                // Centered main container (similar to Canva, light blue)
                .append("<table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px; background-color:#c2e1ff;\">")
                .append("<tr><td style=\"padding:10px 0;\">")

                // Inner dark green panel
                .append("<table width=\"100%\" border=\"0\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px; margin:0 auto; background-color:#084410; color:#c2e1ff; font-family:Arial, Helvetica, sans-serif;\">")
                .append("<tr><td align=\"center\" style=\"padding:16px 16px 8px 16px;\">")

                // Small top spacer
                .append("<div style=\"height:8px;\"></div>")

                // Top title (Fremont Dog Plaza)
                .append("<div style=\"font-family:'Times New Roman', Times, serif; font-size:18px; color:#c2e1ff; text-align:center; margin-bottom:8px;\">")
                .append("Fremont Dog Plaza")
                .append("</div>")

                // Main heading
                .append("<div style=\"font-size:24px; font-weight:bold; color:#c2e1ff; text-align:center; margin-bottom:8px;\">")
                .append("We’ve received your evaluation, ").append(clientName).append("!")
                .append("</div>")

                // Image row (large rectangular graphic)
                .append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"margin-top:8px;\">")
                .append("<tr><td align=\"center\" style=\"padding:0 16px 16px 16px;\">")
                .append("<img src=\"").append(imageUrl).append("\" ")
                .append("alt=\"Dog daycare\" ")
                .append("style=\"display:block; width:100%; max-width:399px; height:auto; border-radius:12px;\" />")
                .append("</td></tr>")
                .append("</table>")

                // Main body text below image
                .append("<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"color:#c2e1ff;\">")
                .append("<tr><td style=\"padding:0 24px 24px 24px;\">")

                // Dog name + breed
                .append("<p style=\"margin:12px 0; font-size:15px; line-height:1.6;\">")
                .append("Evaluation received for <strong>").append(dogName).append("</strong>");
        if (!dogBreed.isBlank()) {
            sb.append(" (").append(dogBreed).append(")");
        }
        sb.append(".")
                .append("</p>");

        // Extras sentence
        sb.append("<p style=\"margin:8px 0; font-size:15px; line-height:1.6;\">")
                .append(extrasSentence)
                .append("</p>");

        // 3–5 business days / visit wording
        sb.append("<p style=\"margin:8px 0; font-size:15px; line-height:1.6;\">")
                .append("We will review your information and reach out within ")
                .append("<strong>3–5 business days</strong> with next steps.")
                .append("</p>");

        // Vaccine/license reminder
        sb.append("<p style=\"margin:8px 0; font-size:15px; line-height:1.6;\">")
                .append("If you weren’t able to upload vaccine or pet license records yet, you can bring them to your visit ")
                .append("or email them to us ahead of time at ")
                .append("<strong>").append(businessEmail).append("</strong>.")
                .append("</p>");

        // Sign-off
        sb.append("<p style=\"margin:16px 0 0 0; font-size:15px; line-height:1.6;\">")
                .append("Talk soon,<br/>")
                .append("<strong>Fremont Dog Plaza</strong>")
                .append("</p>");

        sb.append("</td></tr></table>")   // end text table
                .append("</td></tr>")          // end inner td
                .append("</table>")            // end dark green panel
                .append("</td></tr>")          // end outer td
                .append("</table>")            // end light blue container
                .append("</td></tr>")
                .append("</table>")            // end full-width background
                .append("</body>")
                .append("</html>");

        return sb.toString();
    }
}
