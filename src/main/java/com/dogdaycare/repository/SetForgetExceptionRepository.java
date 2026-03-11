package com.dogdaycare.repository;

import com.dogdaycare.model.SetForgetException;
import com.dogdaycare.model.SetForgetPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SetForgetExceptionRepository extends JpaRepository<SetForgetException, Long> {

    Optional<SetForgetException> findByPlanAndExceptionDate(SetForgetPlan plan, LocalDate exceptionDate);

    List<SetForgetException> findByPlan(SetForgetPlan plan);

    boolean existsByPlanAndExceptionDate(SetForgetPlan plan, LocalDate exceptionDate);
}