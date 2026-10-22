package com.dogdaycare.repository;

import com.dogdaycare.model.EmployeeSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;

public interface EmployeeScheduleRepository extends JpaRepository<EmployeeSchedule, Long> {

    // Retrieve an employee's schedule, ordered by date and start time
    List<EmployeeSchedule> findByEmployeeIdOrderByScheduleDateAscStartTimeAsc(Long employeeId);

    // Retrieve scheduled periods for one employee on a specific day
    List<EmployeeSchedule> findByEmployeeIdAndScheduleDateOrderByStartTimeAsc(
            Long employeeId,
            LocalDate scheduleDate
    );

    // Retrieve all employee schedules for a date range
// Include employee details for the admin calendar
    @EntityGraph(attributePaths = "employee")
    List<EmployeeSchedule> findByScheduleDateBetweenOrderByScheduleDateAscStartTimeAsc(
            LocalDate startDate,
            LocalDate endDate
    );
}
