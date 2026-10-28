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
            LocalDateTime adjustedClockIn,
            LocalDateTime adjustedClockOut) {

        if (entryId == null) {
            throw new IllegalArgumentException("Time entry ID is required.");
        }

        EmployeeTimeEntry entry = timeEntryRepository.findById(entryId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Time entry not found."));

        // The original clock-out must be recorded before adjustments.
        if (entry.getClockOut() == null) {
            throw new IllegalArgumentException(
                    "Record the employee's original clock-out before making adjustments.");
        }

        // Adjusted timestamps must always have values once saved.
        if (adjustedClockIn == null || adjustedClockOut == null) {
            throw new IllegalArgumentException(
                    "Adjusted clock-in and clock-out times are required.");
        }

        if (adjustedClockOut.isBefore(adjustedClockIn)) {
            throw new IllegalArgumentException(
                    "Adjusted clock-out cannot be before adjusted clock-in.");
        }

        Long employeeId = entry.getEmployee().getId();

        List<EmployeeTimeEntry> otherEntries =
                timeEntryRepository.findByEmployeeIdOrderByClockInDesc(employeeId);

        for (EmployeeTimeEntry other : otherEntries) {

            if (other.getId().equals(entryId)) {
                continue;
            }

            // Use adjusted values where available.
            LocalDateTime otherClockIn =
                    other.getAdjustedClockIn() != null
                            ? other.getAdjustedClockIn()
                            : other.getClockIn();

            LocalDateTime otherClockOut =
                    other.getAdjustedClockOut() != null
                            ? other.getAdjustedClockOut()
                            : other.getClockOut();

            boolean overlaps =
                    (otherClockIn.isBefore(adjustedClockOut))
                            && (otherClockOut == null
                            || adjustedClockIn.isBefore(otherClockOut));

            if (overlaps) {
                throw new IllegalArgumentException(
                        "The adjusted times overlap another time entry for this employee.");
            }
        }

        // Preserve the original clock-in and clock-out.
        // Only update the separate adjusted timestamps.
        entry.setAdjustedClockIn(adjustedClockIn);
        entry.setAdjustedClockOut(adjustedClockOut);

        return timeEntryRepository.save(entry);
    }

    // Admin: record a missing original clock-out time.
    @Transactional
    public EmployeeTimeEntry adminClockOut(
            Long entryId,
            LocalDateTime clockOut) {

        if (entryId == null) {
            throw new IllegalArgumentException("Time entry ID is required.");
        }

        if (clockOut == null) {
            throw new IllegalArgumentException("Clock-out date and time are required.");
        }

        EmployeeTimeEntry entry = timeEntryRepository.findById(entryId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Time entry not found."));

        // Never overwrite an existing original clock-out.
        if (entry.getClockOut() != null) {
            throw new IllegalArgumentException(
                    "This employee already has a recorded clock-out.");
        }

        if (clockOut.isBefore(entry.getClockIn())) {
            throw new IllegalArgumentException(
                    "Clock-out time cannot be before clock-in time.");
        }

        // Check against other time entries for this employee.
        List<EmployeeTimeEntry> otherEntries =
                timeEntryRepository.findByEmployeeIdOrderByClockInDesc(
                        entry.getEmployee().getId());

        for (EmployeeTimeEntry other : otherEntries) {

            if (other.getId().equals(entryId)) {
                continue;
            }

            LocalDateTime otherClockIn =
                    other.getAdjustedClockIn() != null
                            ? other.getAdjustedClockIn()
                            : other.getClockIn();

            LocalDateTime otherClockOut =
                    other.getAdjustedClockOut() != null
                            ? other.getAdjustedClockOut()
                            : other.getClockOut();

            boolean overlaps =
                    otherClockIn.isBefore(clockOut)
                            && (otherClockOut == null
                            || entry.getClockIn().isBefore(otherClockOut));

            if (overlaps) {
                throw new IllegalArgumentException(
                        "The clock-out time overlaps another time entry for this employee.");
            }
        }

        // Save the original clock-out once.
        // Future admin adjustments use adjustedClockOut instead.
        entry.setClockOut(clockOut);

        return timeEntryRepository.save(entry);
    }
}