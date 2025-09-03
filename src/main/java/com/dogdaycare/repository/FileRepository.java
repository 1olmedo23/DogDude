package com.dogdaycare.repository;

import com.dogdaycare.model.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface FileRepository extends JpaRepository<UploadedFile, Long> {

    // Customer portal
    List<UploadedFile> findByUserIdOrderByCreatedAtDesc(Long userId);

    // Admin filters
    List<UploadedFile> findByExpirationDateBeforeOrderByExpirationDateAsc(LocalDate date);
    List<UploadedFile> findByExpirationDateBetweenOrderByExpirationDateAsc(LocalDate start, LocalDate end);

    // For grouping by customers quickly
    List<UploadedFile> findByUserIdInOrderByUserIdAscCreatedAtDesc(List<Long> userIds);

    // Evaluation uploads (existing flow)
    List<UploadedFile> findByEvaluationRequestIdOrderByCreatedAtDesc(Long evaluationRequestId);
}
