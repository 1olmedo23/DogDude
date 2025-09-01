package com.dogdaycare.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class PricingService {

    // Canonical keys (all lowercase)
    private static final String DAYCARE_6_TO_3 = "6am - 3pm daycare";
    private static final String DAYCARE_6_TO_8 = "6am - 8pm daycare";
    private static final String BOARDING       = "boarding";

    // Price table (edit here if prices change)
    private static final Map<String, BigDecimal> PRICES = Map.of(
            DAYCARE_6_TO_3, new BigDecimal("45.00"),
            DAYCARE_6_TO_8, new BigDecimal("60.00"),
            BOARDING,       new BigDecimal("80.00")
    );

    public BigDecimal priceFor(String serviceType) {
        if (serviceType == null) return BigDecimal.ZERO;

        String canonical = canonicalize(serviceType);
        BigDecimal price = PRICES.get(canonical);
        if (price == null) {
            // Fallback: try the raw-lowercased value in case it already matches our keys exactly
            String rawLower = serviceType.toLowerCase(Locale.ROOT).trim();
            price = PRICES.get(rawLower);
        }
        if (price == null) {
            log.warn("Unknown serviceType label '{}', canonicalized='{}'. Pricing as $0.00. " +
                    "Update PricingService aliases if this is valid.", serviceType, canonical);
            return BigDecimal.ZERO;
        }
        return price;
    }

    /** Normalize common variations: case, extra spaces, different dash chars, order, synonyms. */
    private String canonicalize(String in) {
        String s = in.toLowerCase(Locale.ROOT).trim();

        // Normalize dashes and collapse spaces
        s = s.replace('\u2013', '-')  // en dash
                .replace('\u2014', '-')  // em dash
                .replaceAll("\\s*-\\s*", " - ")   // "6am-3pm" -> "6am - 3pm"
                .replaceAll("\\s+", " ");         // collapse multiple spaces

        // Heuristics for known services
        boolean hasDaycare = s.contains("daycare");
        boolean hasBoard   = s.contains("board"); // "boarding" / "board"
        boolean has6am     = s.contains("6am") || s.contains("6 am");
        boolean has3pm     = s.contains("3pm") || s.contains("3 pm");
        boolean has8pm     = s.contains("8pm") || s.contains("8 pm");

        if (hasBoard) {
            return BOARDING;
        }

        if (hasDaycare) {
            // Accept both orders: "daycare 6am - 3pm" OR "6am - 3pm daycare"
            if (has6am && has3pm) return DAYCARE_6_TO_3;
            if (has6am && has8pm) return DAYCARE_6_TO_8;

            // Additional loose patterns (just in case future labels drift slightly)
            if (s.contains("half") || s.contains("am only")) return DAYCARE_6_TO_3;
            if (s.contains("full") || s.contains("all day") || s.contains("pm")) return DAYCARE_6_TO_8;
        }

        return s; // fallback: return normalized raw string
    }
}
