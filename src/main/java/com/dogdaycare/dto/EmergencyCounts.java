package com.dogdaycare.dto;

import java.time.LocalDate;

public class EmergencyCounts {

    private final LocalDate date;

    // counts (non-canceled)
    private final int total;
    private final int daycare;
    private final int boarding;
    private final int emergencyUsed;

    // caps (configurable)
    private final int totalCap;
    private final int daycareCap;
    private final int boardingCap;
    private final int emergencyCap;

    public EmergencyCounts(
            LocalDate date,
            int total,
            int daycare,
            int boarding,
            int emergencyUsed,
            int totalCap,
            int daycareCap,
            int boardingCap,
            int emergencyCap
    ) {
        this.date = date;
        this.total = total;
        this.daycare = daycare;
        this.boarding = boarding;
        this.emergencyUsed = emergencyUsed;
        this.totalCap = totalCap;
        this.daycareCap = daycareCap;
        this.boardingCap = boardingCap;
        this.emergencyCap = emergencyCap;
    }

    // ---- getters used in controllers/templates ----

    public LocalDate getDate() { return date; }

    public int getTotal() { return total; }
    public int getDaycare() { return daycare; }
    public int getBoarding() { return boarding; }
    public int getEmergencyUsed() { return emergencyUsed; }

    // Caps exposed as methods (so Thymeleaf calls like counts.totalCap() work)
    public int totalCap() { return totalCap; }
    public int daycareCap() { return daycareCap; }
    public int boardingCap() { return boardingCap; }
    public int emergencyCap() { return emergencyCap; }

    public int getTotalCap() { return totalCap; }
    public int getDaycareCap() { return daycareCap; }
    public int getBoardingCap() { return boardingCap; }
    public int getEmergencyCap() { return emergencyCap; }

    // Remaining emergency spots for the day
    public int emergencyRemaining() { return Math.max(0, emergencyCap - emergencyUsed); }
    // Also provide JavaBean-style getter in case your controller calls getEmergencyRemaining()
    public int getEmergencyRemaining() { return emergencyRemaining(); }

    // Optional helpers (not required, but handy)
    public int totalPercent() { return percent(total, totalCap); }
    public int daycarePercent() { return percent(daycare, daycareCap); }
    public int boardingPercent() { return percent(boarding, boardingCap); }
    public int emergencyPercent() { return percent(emergencyUsed, emergencyCap); }

    private int percent(int value, int cap) {
        if (cap <= 0) return 0;
        return (int)Math.round((100.0 * value) / cap);
    }
}
