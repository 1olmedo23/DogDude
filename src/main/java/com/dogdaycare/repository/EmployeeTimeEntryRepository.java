
package com.dogdaycare.repository;

import com.dogdaycare.model.EmployeeTimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface EmployeeTimeEntryRepository
        extends JpaRepository<EmployeeTimeEntry, Long> {

    // Find an employee's currently open clock-in.
    Optional<EmployeeTimeEntry> findByEmployeeIdAndClockOutIsNull(
            Long employeeId);

    // Retrieve the employee's time records, newest first.
    List<EmployeeTimeEntry> findByEmployeeIdOrderByClockInDesc(
            Long employeeId);

    @EntityGraph(attributePaths = "employee")
    List<EmployeeTimeEntry> findAllByOrderByClockInDesc();
}