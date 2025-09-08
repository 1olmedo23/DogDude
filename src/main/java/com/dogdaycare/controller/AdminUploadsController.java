package com.dogdaycare.controller;

import com.dogdaycare.model.UploadedFile;
import com.dogdaycare.repository.FileRepository;
import com.dogdaycare.service.UploadService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.nio.file.Path;

@Controller
@RequestMapping("/admin/uploads")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUploadsController {

    private final UploadService uploadService;
    private final FileRepository fileRepository;

    public AdminUploadsController(UploadService uploadService, FileRepository fileRepository) {
        this.uploadService = uploadService;
        this.fileRepository = fileRepository;
    }

    // Preserve your existing redirect/filter behavior to keep the Uploads tab open
    @GetMapping
    public String redirectToAdminUploadsTab(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
            RedirectAttributes ra
    ) {
        if (q != null && !q.isBlank()) {
            ra.addAttribute("q", q);
        }
        ra.addAttribute("filter", filter);
        ra.addAttribute("openTab", "uploads");
        return "redirect:/admin";
    }

    // NEW: Admin download any uploaded file
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> adminDownload(@PathVariable Long id) {
        UploadedFile uf = fileRepository.findById(id).orElse(null);
        if (uf == null) return ResponseEntity.notFound().build();

        Path path = uploadService.resolveDownloadPath(uf);
        File file = path.toFile();
        if (!file.exists()) return ResponseEntity.notFound().build();

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + uf.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(
                        uf.getFileType() != null ? uf.getFileType() : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .contentLength(file.length())
                .body(resource);
    }

    // NEW: Admin delete (removes file from disk and DB)
    @PostMapping("/{id}/delete")
    public String adminDelete(@PathVariable Long id, RedirectAttributes ra) {
        UploadedFile uf = fileRepository.findById(id).orElse(null);
        if (uf == null) {
            ra.addFlashAttribute("errorMessage", "File not found.");
            ra.addAttribute("openTab", "uploads");
            return "redirect:/admin";
        }

        try {
            // 1) Remove from disk (ignore if already missing)
            Path path = uploadService.resolveDownloadPath(uf);
            File fileOnDisk = path.toFile();
            if (fileOnDisk.exists() && !fileOnDisk.delete()) {
                // If delete fails, we still proceed to remove DB record but let admin know
                ra.addFlashAttribute("errorMessage", "File record removed, but disk file could not be deleted.");
            }

            // 2) Remove DB record
            fileRepository.deleteById(id);

            ra.addFlashAttribute("successMessage", "File deleted.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Failed to delete file.");
        }

        ra.addAttribute("openTab", "uploads");
        return "redirect:/admin";
    }
}
