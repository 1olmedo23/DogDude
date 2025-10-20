package com.dogdaycare.dto;

import java.math.BigDecimal;
import java.time.LocalTime;

public class BookingRowDto {
    private Long id;
    private String customerName;
    private String customerEmail;
    private String dogName;
    private String serviceType;
    private LocalTime time;
    private String status;

    // Prepay signals
    private boolean wantsAdvancePay;
    private boolean advanceEligible;

    // Paid (day-level)
    private boolean paid;

    // Locked price (if present)
    private BigDecimal quotedRateAtLock;

    // Multi-dog + live (tier-aware) amount
    private Integer dogCount;
    private BigDecimal liveAmount;

    public BookingRowDto() {}

    // Existing ctor (kept for compatibility)
    public BookingRowDto(Long id,
                         String customerName,
                         String customerEmail,
                         String dogName,
                         String serviceType,
                         LocalTime time,
                         String status,
                         boolean wantsAdvancePay,
                         boolean advanceEligible,
                         boolean paid,
                         BigDecimal quotedRateAtLock,
                         Integer dogCount) {
        this.id = id;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.dogName = dogName;
        this.serviceType = serviceType;
        this.time = time;
        this.status = status;
        this.wantsAdvancePay = wantsAdvancePay;
        this.advanceEligible = advanceEligible;
        this.paid = paid;
        this.quotedRateAtLock = quotedRateAtLock;
        this.dogCount = dogCount;
    }

    // New ctor that includes liveAmount (used by AdminBookingController)
    public BookingRowDto(Long id,
                         String customerName,
                         String customerEmail,
                         String dogName,
                         String serviceType,
                         LocalTime time,
                         String status,
                         boolean wantsAdvancePay,
                         boolean advanceEligible,
                         boolean paid,
                         BigDecimal quotedRateAtLock,
                         Integer dogCount,
                         BigDecimal liveAmount) {
        this(id, customerName, customerEmail, dogName, serviceType, time, status,
                wantsAdvancePay, advanceEligible, paid, quotedRateAtLock, dogCount);
        this.liveAmount = liveAmount;
    }

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public String getDogName() { return dogName; }
    public void setDogName(String dogName) { this.dogName = dogName; }

    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }

    public LocalTime getTime() { return time; }
    public void setTime(LocalTime time) { this.time = time; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isWantsAdvancePay() { return wantsAdvancePay; }
    public void setWantsAdvancePay(boolean wantsAdvancePay) { this.wantsAdvancePay = wantsAdvancePay; }

    public boolean isAdvanceEligible() { return advanceEligible; }
    public void setAdvanceEligible(boolean advanceEligible) { this.advanceEligible = advanceEligible; }

    public boolean isPaid() { return paid; }
    public void setPaid(boolean paid) { this.paid = paid; }

    public BigDecimal getQuotedRateAtLock() { return quotedRateAtLock; }
    public void setQuotedRateAtLock(BigDecimal quotedRateAtLock) { this.quotedRateAtLock = quotedRateAtLock; }

    public Integer getDogCount() { return dogCount; }
    public void setDogCount(Integer dogCount) { this.dogCount = dogCount; }

    public BigDecimal getLiveAmount() { return liveAmount; }
    public void setLiveAmount(BigDecimal liveAmount) { this.liveAmount = liveAmount; }
}
