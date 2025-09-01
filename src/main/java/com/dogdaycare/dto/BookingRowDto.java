package com.dogdaycare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BookingRowDto {
    private Long id;
    private String customerName;   // derived from EvaluationRequest.clientName (fallback: email)
    private String customerEmail;  // booking.customer.username (email)
    private String dogName;        // derived from EvaluationRequest.dogName (fallback: "N/A")
    private String serviceType;
    private String time;           // serialize as string for JS table
    private String status;
}
