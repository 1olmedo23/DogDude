package com.dogdaycare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InvoiceRowDto {
    private Long invoiceId;            // null if not yet persisted (unpaid calc row)
    private String customerName;
    private String customerEmail;
    private String dogName;

    // Live total for the week (recomputed from current bookings)
    private BigDecimal amount;

    // Row paid state: true ONLY if invoice was paid AND all current bookings are paid
    private boolean paid;

    // NEW: clarity for “added after payment” scenarios
    private BigDecimal previouslyPaidAmount; // 0 if no paid invoice exists
    private BigDecimal deltaUnpaid;          // max(amount - previouslyPaidAmount, 0)
    private boolean invoicePaid;             // whether an invoice exists AND is marked paid
}
