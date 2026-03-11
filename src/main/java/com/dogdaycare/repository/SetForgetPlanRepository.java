package com.dogdaycare.repository;

import com.dogdaycare.model.SetForgetPlan;
import com.dogdaycare.model.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SetForgetPlanRepository extends JpaRepository<SetForgetPlan, Long> {

    @EntityGraph(attributePaths = {"rules"})
    Optional<SetForgetPlan> findByCustomerAndActiveTrue(User customer);
}