
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

@Controller
@RequestMapping("/employee")
public class EmployeeController {

    private final UserRepository userRepository;
    private final EmployeeScheduleRepository employeeScheduleRepository;

    public EmployeeController(
            UserRepository userRepository,
            EmployeeScheduleRepository employeeScheduleRepository) {

        this.userRepository = userRepository;
        this.employeeScheduleRepository = employeeScheduleRepository;
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

        return "employee";
    }
}