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
import org.springframework.ui.Model;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

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

    @GetMapping
    public String uploadsHome(Authentication auth, Model model) {
        User user = userRepository.findByUsername(auth.getName()).orElseThrow();
        List<UploadedFile> files = uploadService.listForUser(user);
        model.addAttribute("files", files);
        model.addAttribute("activePage", "uploads");
        return "uploads"; // templates/uploads.html
    }

    @PostMapping
    public String handleUpload(Authentication auth,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "displayName", required = false) String displayName,
                               @RequestParam(value = "expirationDate", required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expirationDate,
                               Model model) {
        try {
            User user = userRepository.findByUsername(auth.getName()).orElseThrow();
            uploadService.storeForUser(user, file, displayName, expirationDate);
            return "redirect:/uploads";
        } catch (Exception e) {
            model.addAttribute("errorMessage", "Upload failed: " + e.getMessage());
            return uploadsHome(auth, model);
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(Authentication auth, @PathVariable Long id) {
        User user = userRepository.findByUsername(auth.getName()).orElseThrow();
        UploadedFile uf = fileRepository.findById(id).orElseThrow();

        // Only owner can download
        if (uf.getUser() == null || !uf.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }

        Path path = uploadService.resolveDownloadPath(uf);
        File file = path.toFile();
        if (!file.exists()) return ResponseEntity.notFound().build();

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + uf.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(uf.getFileType() != null ? uf.getFileType() : MimeTypeUtils.APPLICATION_OCTET_STREAM_VALUE))
                .contentLength(file.length())
                .body(resource);
    }
}
