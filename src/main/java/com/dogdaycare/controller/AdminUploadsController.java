package com.dogdaycare.controller;

import com.dogdaycare.model.UploadedFile;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.FileRepository;
import com.dogdaycare.repository.UserRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/uploads")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUploadsController {

    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    public AdminUploadsController(FileRepository fileRepository, UserRepository userRepository) {
        this.fileRepository = fileRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String list(@RequestParam(value = "q", required = false) String q,
                       @RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
                       Model model) {

        List<User> users;
        if (q != null && !q.isBlank()) {
            users = userRepository.findAll().stream()
                    .filter(u -> u.getUsername() != null &&
                            u.getUsername().toLowerCase().contains(q.toLowerCase()))
                    .collect(Collectors.toList());
        } else {
            users = userRepository.findAll();
        }

        List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());
        Map<Long, List<UploadedFile>> byUser = new LinkedHashMap<>();

        List<UploadedFile> files;
        LocalDate today = LocalDate.now();

        switch (filter) {
            case "expired" -> files = fileRepository.findByExpirationDateBeforeOrderByExpirationDateAsc(today);
            case "expiring" -> files = fileRepository.findByExpirationDateBetweenOrderByExpirationDateAsc(today, today.plusDays(30));
            default -> files = userIds.isEmpty()
                    ? Collections.emptyList()
                    : fileRepository.findByUserIdInOrderByUserIdAscCreatedAtDesc(userIds);
        }

        // Group by user
        for (UploadedFile f : files) {
            if (f.getUser() == null) continue;
            byUser.computeIfAbsent(f.getUser().getId(), k -> new ArrayList<>()).add(f);
        }

        model.addAttribute("q", q);
        model.addAttribute("filter", filter);
        model.addAttribute("users", users);       // so we can show names/emails
        model.addAttribute("byUser", byUser);     // map userId -> files
        model.addAttribute("activePage", "admin-uploads");
        return "admin/uploads"; // templates/admin/uploads.html
    }
}
