package com.dogdaycare.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Authentication auth, Model model) {
        // If authenticated, route to the appropriate dashboard and avoid landing page.
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            var roles = auth.getAuthorities().toString();
            if (roles.contains("ROLE_ADMIN")) {
                return "redirect:/admin";
            }
            if (roles.contains("ROLE_CUSTOMER")) {
                return "redirect:/booking";
            }
        }
        // Anonymous users see landing with the Home tab highlighted.
        model.addAttribute("activePage", "home");
        return "landing";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("activePage", "login");
        return "login";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("activePage", "about");
        return "about";
    }
}
