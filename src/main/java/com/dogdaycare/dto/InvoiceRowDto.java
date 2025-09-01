package com.dogdaycare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class InvoiceRowDto {
    private Long invoiceId;        // null if not yet persisted (unpaid calc row)
    private String customerName;
    private String customerEmail;
    private String dogName;
    private BigDecimal amount;
    private boolean paid;
}
