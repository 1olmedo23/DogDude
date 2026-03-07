package com.dogdaycare.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetForgetRuleRequest {

    // 1 = Monday ... 7 = Sunday
    private short dayOfWeek;

    // Must match existing booking service strings exactly
    private String serviceType;

    // "06:00", "06:30", etc.
    private String dropoffTime;
}