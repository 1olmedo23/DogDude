package com.dogdaycare.repository;

import com.dogdaycare.model.SetForgetRule;
import com.dogdaycare.model.SetForgetPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SetForgetRuleRepository extends JpaRepository<SetForgetRule, Long> {
    List<SetForgetRule> findByPlanAndActiveTrue(SetForgetPlan plan);
}
