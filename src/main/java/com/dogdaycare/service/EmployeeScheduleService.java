package com.dogdaycare.service;

import com.dogdaycare.model.EmployeeSchedule;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.EmployeeScheduleRepository;
import com.dogdaycare.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
public class EmployeeScheduleService {

    private final EmployeeScheduleRepository scheduleRepository;
    private final UserRepository userRepository;

    public EmployeeScheduleService(EmployeeScheduleRepository scheduleRepository,
                                   UserRepository userRepository) {
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public EmployeeSchedule createSchedule(Long employeeId,
                                           LocalDate scheduleDate,
                                           LocalTime startTime,
                                           LocalTime endTime) {

        // Verify that the account belongs to an active employee
        User employee = userRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found."));

        if (!"EMPLOYEE".equalsIgnoreCase(employee.getRole()) || !employee.isEnabled()) {
            throw new IllegalArgumentException("Only active employees can be scheduled.");
        }

        if (scheduleDate == null || startTime == null || endTime == null) {
            throw new IllegalArgumentException("Date, start time, and end time are required.");
        }

        // All scheduled working periods must start and end on the same day
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("Start time must be before end time.");
        }

        List<EmployeeSchedule> existingSchedules =
                scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                        employeeId, scheduleDate);

        // Maximum two scheduled working periods per employee per day
        if (existingSchedules.size() >= 2) {
            throw new IllegalArgumentException(
                    "An employee can have a maximum of two scheduled periods per day.");
        }

        // Prevent overlapping scheduled periods
        for (EmployeeSchedule existing : existingSchedules) {
            boolean overlaps = startTime.isBefore(existing.getEndTime())
                    && endTime.isAfter(existing.getStartTime());

            if (overlaps) {
                throw new IllegalArgumentException(
                        "The scheduled times overlap an existing period.");
            }
        }

        EmployeeSchedule schedule = new EmployeeSchedule();
        schedule.setEmployee(employee);
        schedule.setScheduleDate(scheduleDate);
        schedule.setStartTime(startTime);
        schedule.setEndTime(endTime);

        return scheduleRepository.save(schedule);
    }


    @Transactional
    public List<EmployeeSchedule> createSchedules(
            Long employeeId,
            List<LocalDate> scheduleDates,
            LocalTime startTime,
            LocalTime endTime) {

        if (scheduleDates == null || scheduleDates.isEmpty()) {
            throw new IllegalArgumentException("Select at least one date.");
        }

        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("Start time must be before end time.");
        }

        User employee = userRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found."));

        if (!"EMPLOYEE".equalsIgnoreCase(employee.getRole()) || !employee.isEnabled()) {
            throw new IllegalArgumentException("Only active employees can be scheduled.");
        }

        // Remove duplicate dates while preserving their order.
        Set<LocalDate> uniqueDates = new LinkedHashSet<>(scheduleDates);

        if (uniqueDates.contains(null)) {
            throw new IllegalArgumentException("Invalid schedule date.");
        }

        // Validate every selected date before saving anything.
        for (LocalDate date : uniqueDates) {

            List<EmployeeSchedule> existingSchedules =
                    scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                            employeeId, date);

            if (existingSchedules.size() >= 2) {
                throw new IllegalArgumentException(
                        "The employee already has two scheduled periods on " + date + ".");
            }

            for (EmployeeSchedule existing : existingSchedules) {
                boolean overlaps = startTime.isBefore(existing.getEndTime())
                        && endTime.isAfter(existing.getStartTime());

                if (overlaps) {
                    throw new IllegalArgumentException(
                            "The selected times overlap an existing schedule on " + date + ".");
                }
            }
        }

        // All selected dates passed validation.
        List<EmployeeSchedule> schedules = new ArrayList<>();

        for (LocalDate date : uniqueDates) {
            EmployeeSchedule schedule = new EmployeeSchedule();
            schedule.setEmployee(employee);
            schedule.setScheduleDate(date);
            schedule.setStartTime(startTime);
            schedule.setEndTime(endTime);

            schedules.add(schedule);
        }

        return scheduleRepository.saveAll(schedules);
    }


    @Transactional
    public void deleteSchedule(Long scheduleId) {

        if (scheduleId == null) {
            throw new IllegalArgumentException("Schedule ID is required.");
        }

        EmployeeSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Scheduled period not found."));

        scheduleRepository.delete(schedule);
    }


    @Transactional
    public EmployeeSchedule updateSchedule(
            Long scheduleId,
            Long employeeId,
            LocalDate scheduleDate,
            LocalTime startTime,
            LocalTime endTime) {

        if (scheduleId == null) {
            throw new IllegalArgumentException("Schedule ID is required.");
        }

        if (scheduleDate == null || startTime == null || endTime == null) {
            throw new IllegalArgumentException(
                    "Date, start time, and end time are required.");
        }

        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException(
                    "Start time must be before end time.");
        }

        EmployeeSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Scheduled period not found."));

        User employee = userRepository.findById(employeeId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Employee not found."));

        if (!"EMPLOYEE".equalsIgnoreCase(employee.getRole()) || !employee.isEnabled()) {
            throw new IllegalArgumentException(
                    "Only active employees can be scheduled.");
        }

        List<EmployeeSchedule> existingSchedules =
                scheduleRepository.findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
                        employeeId, scheduleDate);

        // Exclude the period currently being edited.
        List<EmployeeSchedule> otherSchedules = existingSchedules.stream()
                .filter(existing -> !existing.getId().equals(scheduleId))
                .toList();

        if (otherSchedules.size() >= 2) {
            throw new IllegalArgumentException(
                    "An employee can have a maximum of two scheduled periods per day.");
        }

        for (EmployeeSchedule existing : otherSchedules) {
            boolean overlaps = startTime.isBefore(existing.getEndTime())
                    && endTime.isAfter(existing.getStartTime());

            if (overlaps) {
                throw new IllegalArgumentException(
                        "The scheduled times overlap an existing period.");
            }
        }

        schedule.setEmployee(employee);
        schedule.setScheduleDate(scheduleDate);
        schedule.setStartTime(startTime);
        schedule.setEndTime(endTime);

        return scheduleRepository.save(schedule);
    }
}
