package com.dogdaycare.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class EmergencyCounts {
    private final long daycare;       // non-canceled
    private final long boarding;      // non-canceled
    private final long total;         // daycare + boarding
    private final long emergencyUsed; // computed from totals

    public long daycareCap()  { return 40; }
    public long boardingCap() { return 20; }
    public long totalCap()    { return 70; }
    public long emergencyCap(){ return 10; }

    public long emergencyRemaining() {
        long rem = emergencyCap() - emergencyUsed;
        return Math.max(rem, 0);
    }
}
