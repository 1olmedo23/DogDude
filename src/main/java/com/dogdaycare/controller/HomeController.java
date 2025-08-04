package com.dogdaycare.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("activePage", "home"); // highlights Home in navbar
        return "landing";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("activePage", "login"); // highlights Login in navbar
        return "login";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("activePage", "about"); // highlights About in navbar
        return "about";
    }
}