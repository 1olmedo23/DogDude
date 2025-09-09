// src/main/java/com/dogdaycare/dto/BookingRowDto.java
package com.dogdaycare.dto;

import java.time.LocalTime;

public class BookingRowDto {
    private Long id;
    private String customerName;
    private String dogName;       // if you already return this; otherwise keep as you have
    private String serviceType;
    private LocalTime time;
    private String status;

    // NEW: prepay signal
    private boolean wantsAdvancePay;
    private boolean advanceEligible;

    public BookingRowDto() {}

    public BookingRowDto(Long id,
                         String customerName,
                         String dogName,
                         String serviceType,
                         LocalTime time,
                         String status,
                         boolean wantsAdvancePay,
                         boolean advanceEligible) {
        this.id = id;
        this.customerName = customerName;
        this.dogName = dogName;
        this.serviceType = serviceType;
        this.time = time;
        this.status = status;
        this.wantsAdvancePay = wantsAdvancePay;
        this.advanceEligible = advanceEligible;
    }

    // getters & setters …

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

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
}
