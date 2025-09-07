package com.dogdaycare.controller;

import com.dogdaycare.model.UploadedFile;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.FileRepository;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.UploadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;

@Controller
@RequestMapping("/uploads")
public class UploadController {

    private final UploadService uploadService;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;

    public UploadController(UploadService uploadService,
                            UserRepository userRepository,
                            FileRepository fileRepository) {
        this.uploadService = uploadService;
        this.userRepository = userRepository;
        this.fileRepository = fileRepository;
    }

    // 👇 Always show uploads inside the Booking page tab
    @GetMapping
    public String redirectToBookingUploadsTab() {
        return "redirect:/booking#uploads";
    }

    // After upload, go back to the Booking page on the Uploads tab
    @PostMapping
    public String handleUpload(Authentication auth,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "displayName", required = false) String displayName,
                               @RequestParam(value = "expirationDate", required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expirationDate) {
        try {
            User user = userRepository.findByUsername(auth.getName()).orElseThrow();
            uploadService.storeForUser(user, file, displayName, expirationDate);
        } catch (Exception ignored) {
            // You can add a FlashAttribute error if you want; keeping it simple
        }
        return "redirect:/booking#uploads";
    }

    // Downloads stay here (deep link ok)
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(Authentication auth, @PathVariable Long id) {
        User user = userRepository.findByUsername(auth.getName()).orElseThrow();
        UploadedFile uf = fileRepository.findById(id).orElseThrow();

        if (uf.getUser() == null || !uf.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }

        Path path = uploadService.resolveDownloadPath(uf);
        File file = path.toFile();
        if (!file.exists()) return ResponseEntity.notFound().build();

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + uf.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(uf.getFileType() != null ? uf.getFileType() : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .contentLength(file.length())
                .body(resource);
    }
}
