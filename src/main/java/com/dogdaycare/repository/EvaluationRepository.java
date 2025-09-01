package com.dogdaycare.repository;

import com.dogdaycare.model.EvaluationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EvaluationRepository extends JpaRepository<EvaluationRequest, Long> {
    Optional<EvaluationRequest> findByEmail(String email);

    // Use the most recent evaluation for a given email (for name/dog display)
    Optional<EvaluationRequest> findTopByEmailOrderByCreatedAtDesc(String email);
}