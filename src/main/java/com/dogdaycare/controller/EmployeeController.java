package com.dogdaycare.controller;

import com.dogdaycare.model.User;
import com.dogdaycare.repository.EmployeeScheduleRepository;
import com.dogdaycare.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import com.dogdaycare.model.EmployeeTimeEntry;
import com.dogdaycare.repository.EmployeeTimeEntryRepository;
import com.dogdaycare.service.EmployeeTimeEntryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@Controller
@RequestMapping("/employee")
public class EmployeeController {

    private final UserRepository userRepository;
    private final EmployeeScheduleRepository employeeScheduleRepository;
    private final EmployeeTimeEntryRepository timeEntryRepository;
    private final EmployeeTimeEntryService timeEntryService;

    public EmployeeController(
            UserRepository userRepository,
            EmployeeScheduleRepository employeeScheduleRepository,
            EmployeeTimeEntryRepository timeEntryRepository,
            EmployeeTimeEntryService timeEntryService) {

        this.userRepository = userRepository;
        this.employeeScheduleRepository = employeeScheduleRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.timeEntryService = timeEntryService;
    }

    @GetMapping
    public String employeeDashboard(Authentication authentication, Model model) {

        User employee = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Employee account not found."));

        model.addAttribute("employeeName",
                employee.getFullName() != null
                        ? employee.getFullName()
                        : employee.getUsername());

        model.addAttribute("employeeSchedules",
                employeeScheduleRepository
                        .findByEmployeeIdOrderByScheduleDateAscStartTimeAsc(
                                employee.getId()));

        Optional<EmployeeTimeEntry> activeEntry =
                timeEntryRepository.findByEmployeeIdAndClockOutIsNull(employee.getId());

        model.addAttribute("activeTimeEntry", activeEntry.orElse(null));
        model.addAttribute("clockedIn", activeEntry.isPresent());
        model.addAttribute(
                "employeeTimeEntries",
                timeEntryRepository.findByEmployeeIdOrderByClockInDesc(employee.getId())
        );

        return "employee";
    }


    @PostMapping("/clock-in")
    public String clockIn(Authentication authentication,
                          RedirectAttributes redirectAttributes) {

        try {
            User employee = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Employee account not found."));

            timeEntryService.clockIn(employee.getId());

            redirectAttributes.addFlashAttribute(
                    "success", "Successfully clocked in.");

        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "error", exception.getMessage());
        }

        return "redirect:/employee";
    }

    @PostMapping("/clock-out")
    public String clockOut(Authentication authentication,
                           RedirectAttributes redirectAttributes) {

        try {
            User employee = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Employee account not found."));

            timeEntryService.clockOut(employee.getId());

            redirectAttributes.addFlashAttribute(
                    "success", "Successfully clocked out.");

        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute(
                    "error", exception.getMessage());
        }

        return "redirect:/employee";
    }
}