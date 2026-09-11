package com.dogdaycare.repository;

import com.dogdaycare.model.SetForgetPlan;
import com.dogdaycare.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SetForgetPlanRepository extends JpaRepository<SetForgetPlan, Long> {

    // Keep simultaneous changes to an existing plan from overlapping.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SetForgetPlan p where p.customer = :customer and p.active = true")
    Optional<SetForgetPlan> findByCustomerAndActiveTrue(
            @Param("customer") User customer
    );

    // Load the plan and its rules for display.
    @EntityGraph(attributePaths = {"rules"})
    @Query("select p from SetForgetPlan p where p.customer = :customer and p.active = true")
    Optional<SetForgetPlan> findActivePlanForDisplay(
            @Param("customer") User customer
    );

    @Query("select p.customer from SetForgetPlan p where p.active = true order by p.id")
    List<User> findActivePlanCustomers();
}