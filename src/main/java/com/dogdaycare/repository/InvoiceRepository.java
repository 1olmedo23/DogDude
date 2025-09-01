package com.dogdaycare.repository;

import com.dogdaycare.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByCustomerEmailAndWeekStart(String customerEmail, LocalDate weekStart);
    List<Invoice> findByWeekStart(LocalDate weekStart);
}
