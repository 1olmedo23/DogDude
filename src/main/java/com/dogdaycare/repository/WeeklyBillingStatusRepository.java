package com.dogdaycare.repository;

import com.dogdaycare.model.User;
import com.dogdaycare.model.WeeklyBillingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyBillingStatusRepository extends JpaRepository<WeeklyBillingStatus, Long> {
    Optional<WeeklyBillingStatus> findByUserAndWeekStart(User user, LocalDate weekStart);
}
