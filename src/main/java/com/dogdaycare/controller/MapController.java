package com.dogdaycare.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MapController {

    @GetMapping("/map")
    public String map(Model model) {
        model.addAttribute("activePage", "map");
        return "map";
    }
}
