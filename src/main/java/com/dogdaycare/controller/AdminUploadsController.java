package com.dogdaycare.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/uploads")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUploadsController {

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
}
