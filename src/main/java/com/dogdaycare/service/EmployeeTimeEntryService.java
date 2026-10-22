package com.dogdaycare.service;

import com.dogdaycare.model.EmployeeTimeEntry;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.EmployeeTimeEntryRepository;
import com.dogdaycare.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class EmployeeTimeEntryService {

    private final EmployeeTimeEntryRepository timeEntryRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public EmployeeTimeEntryService(
            EmployeeTimeEntryRepository timeEntryRepository,
            UserRepository userRepository,
            @Value("${app.business.zone:America/Los_Angeles}") String businessZone) {

        this.timeEntryRepository = timeEntryRepository;
        this.userRepository = userRepository;
        this.clock = Clock.system(ZoneId.of(businessZone));
    }

    @Transactional
    public EmployeeTimeEntry clockIn(Long employeeId) {

        if (employeeId == null) {
            throw new IllegalArgumentException("Employee ID is required.");
        }

        User employee = userRepository.findById(employeeId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Employee not found."));

        if (!"EMPLOYEE".equalsIgnoreCase(employee.getRole())
                || !employee.isEnabled()) {
            throw new IllegalArgumentException(
                    "Only active employees can clock in.");
        }

        if (timeEntryRepository
                .findByEmployeeIdAndClockOutIsNull(employeeId)
                .isPresent()) {
            throw new IllegalArgumentException(
                    "You are already clocked in.");
        }

        EmployeeTimeEntry entry = new EmployeeTimeEntry();
        entry.setEmployee(employee);
        entry.setClockIn(LocalDateTime.now(clock));

        try {
            return timeEntryRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "You are already clocked in.", exception);
        }
    }


    @Transactional
    public EmployeeTimeEntry clockOut(Long employeeId) {

        if (employeeId == null) {
            throw new IllegalArgumentException("Employee ID is required.");
        }

        User employee = userRepository.findById(employeeId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Employee not found."));

        if (!"EMPLOYEE".equalsIgnoreCase(employee.getRole())
                || !employee.isEnabled()) {
            throw new IllegalArgumentException(
                    "Only active employees can clock out.");
        }

        EmployeeTimeEntry entry = timeEntryRepository
                .findByEmployeeIdAndClockOutIsNull(employeeId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "You are not currently clocked in."));

        LocalDateTime now = LocalDateTime.now(clock);

        if (now.isBefore(entry.getClockIn())) {
            throw new IllegalArgumentException(
                    "Clock-out time cannot be before clock-in time.");
        }

        entry.setClockOut(now);

        return timeEntryRepository.save(entry);
    }


    @Transactional
    public EmployeeTimeEntry updateTimeEntry(
            Long entryId,
            LocalDateTime clockIn,
            LocalDateTime clockOut) {

        if (entryId == null) {
            throw new IllegalArgumentException("Time entry ID is required.");
        }

        EmployeeTimeEntry entry = timeEntryRepository.findById(entryId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Time entry not found."));

        if (clockIn == null) {
            throw new IllegalArgumentException(
                    "Clock-in date and time are required.");
        }

        if (clockOut != null && clockOut.isBefore(clockIn)) {
            throw new IllegalArgumentException(
                    "Clock-out time cannot be before clock-in time.");
        }


        Long employeeId = entry.getEmployee().getId();

        List<EmployeeTimeEntry> otherEntries =
                timeEntryRepository.findByEmployeeIdOrderByClockInDesc(employeeId);

        for (EmployeeTimeEntry other : otherEntries) {

            // Do not compare this record against itself.
            if (other.getId().equals(entryId)) {
                continue;
            }

            LocalDateTime otherClockIn = other.getClockIn();
            LocalDateTime otherClockOut = other.getClockOut();

            // Treat a missing clock-out as an ongoing work period.
            boolean overlaps =
                    (clockOut == null || otherClockIn.isBefore(clockOut))
                            && (otherClockOut == null || clockIn.isBefore(otherClockOut));

            if (overlaps) {
                throw new IllegalArgumentException(
                        "The corrected times overlap another time entry for this employee.");
            }
        }

        entry.setClockIn(clockIn);
        entry.setClockOut(clockOut);

        return timeEntryRepository.save(entry);
    }

}