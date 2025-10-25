package com.dogdaycare.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class InvoiceRowDto {

    private Long invoiceId;
    private String customerName;
    private String customerEmail;
    private String dogName;

    // Core values (use these internally)
    private BigDecimal amount;              // total for the week
    private boolean paid;                   // "rowPaid" in controller
    private BigDecimal previouslyPaidAmount;
    private BigDecimal deltaUnpaid;         // new charges since last payment
    private boolean invoicePaid;

    // --- Constructors ---

    // Needed by Jackson
    public InvoiceRowDto() {}

    // 9-arg ctor used by AdminInvoiceController#weekly today
    public InvoiceRowDto(
            Long invoiceId,
            String customerName,
            String customerEmail,
            String dogName,
            BigDecimal amount,
            boolean paid,
            BigDecimal previouslyPaidAmount,
            BigDecimal deltaUnpaid,
            boolean invoicePaid
    ) {
        this.invoiceId = invoiceId;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.dogName = dogName;
        this.amount = amount;
        this.paid = paid;
        this.previouslyPaidAmount = previouslyPaidAmount;
        this.deltaUnpaid = deltaUnpaid;
        this.invoicePaid = invoicePaid;
    }

    // --- Id & labels ---
    public Long getInvoiceId() { return invoiceId; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public String getDogName() { return dogName; }

    // --- Amount / Total (publish both names) ---

    // Old name some tests use
    @JsonProperty("amount")
    public BigDecimal getAmountRaw() {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    // New preferred name
    @JsonProperty("total")
    public BigDecimal getTotalAlias() {
        return getAmountRaw();
    }

    // --- Paid flags ---
    public boolean isPaid() { return paid; }
    public boolean isInvoicePaid() { return invoicePaid; }

    // --- Previously paid (publish both names) ---
    @JsonProperty("previouslyPaidAmount")
    public BigDecimal getPreviouslyPaidAmount() {
        return previouslyPaidAmount == null ? null : previouslyPaidAmount.setScale(2, RoundingMode.HALF_UP);
    }

    @JsonProperty("paidToDate")
    public BigDecimal getPaidToDateAlias() {
        return getPreviouslyPaidAmount();
    }

    // --- Delta / New since paid (publish both names) ---
    @JsonProperty("deltaUnpaid")
    public BigDecimal getDeltaUnpaidRaw() {
        return deltaUnpaid == null ? null : deltaUnpaid.setScale(2, RoundingMode.HALF_UP);
    }

    @JsonProperty("newSincePaid")
    public BigDecimal getNewSincePaidAlias() {
        return getDeltaUnpaidRaw();
    }

    // (Optional) setters if your framework needs them
}
