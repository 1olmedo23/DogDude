package com.dogdaycare.controller;

import com.dogdaycare.dto.SetForgetRuleRequest;
import com.dogdaycare.model.SetForgetPlan;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.UserRepository;
import com.dogdaycare.service.SetForgetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/test/set-forget")
public class SetForgetTestController {

    private final SetForgetService setForgetService;
    private final UserRepository userRepository;

    public SetForgetTestController(SetForgetService setForgetService,
                                   UserRepository userRepository) {
        this.setForgetService = setForgetService;
        this.userRepository = userRepository;
    }

    @PostMapping("/save")
    public ResponseEntity<?> save(Authentication authentication,
                                  @RequestBody Map<String, Object> payload) {

        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        boolean wantsAdvancePay = Boolean.TRUE.equals(payload.get("wantsAdvancePay"));

        Integer dogCount = 1;
        Object dogCountObj = payload.get("dogCount");
        if (dogCountObj instanceof Number number) {
            dogCount = number.intValue();
        }

        String durationOption = String.valueOf(payload.get("durationOption"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesRaw = (List<Map<String, Object>>) payload.get("rules");

        List<SetForgetRuleRequest> rules = rulesRaw.stream().map(row -> {
            SetForgetRuleRequest req = new SetForgetRuleRequest();

            Object dayObj = row.get("dayOfWeek");
            if (dayObj instanceof Number number) {
                req.setDayOfWeek((short) number.intValue());
            } else {
                req.setDayOfWeek(Short.parseShort(String.valueOf(dayObj)));
            }

            req.setServiceType(String.valueOf(row.get("serviceType")));
            req.setDropoffTime(String.valueOf(row.get("dropoffTime")));
            return req;
        }).toList();

        SetForgetPlan plan = setForgetService.saveOrUpdatePlan(
                customer,
                wantsAdvancePay,
                dogCount,
                durationOption,
                rules
        );

        return ResponseEntity.ok(Map.of(
                "planId", plan.getId(),
                "active", plan.isActive(),
                "startDate", String.valueOf(plan.getStartDate()),
                "endDate", String.valueOf(plan.getEndDate()),
                "ruleCount", plan.getRules().size()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();
        SetForgetPlan plan = setForgetService.getActivePlan(customer);

        if (plan == null) {
            return ResponseEntity.ok(Map.of("hasPlan", false));
        }

        List<Map<String, Object>> rules = plan.getRules().stream()
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("dayOfWeek", r.getDayOfWeek());
                    m.put("serviceType", r.getServiceType());
                    m.put("dropoffTime", String.valueOf(r.getDropoffTime()));
                    m.put("active", r.isActive());
                    return m;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "hasPlan", true,
                "planId", plan.getId(),
                "active", plan.isActive(),
                "startDate", String.valueOf(plan.getStartDate()),
                "endDate", String.valueOf(plan.getEndDate()),
                "wantsAdvancePay", plan.isWantsAdvancePay(),
                "dogCount", plan.getDogCount(),
                "rules", rules
        ));
    }

    @GetMapping("/seed")
    public ResponseEntity<?> seed(Authentication authentication) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        SetForgetRuleRequest r1 = new SetForgetRuleRequest();
        r1.setDayOfWeek((short) 1);
        r1.setServiceType("Daycare (6 AM - 3 PM)");
        r1.setDropoffTime("06:00");

        SetForgetRuleRequest r2 = new SetForgetRuleRequest();
        r2.setDayOfWeek((short) 3);
        r2.setServiceType("Daycare (6 AM - 8 PM)");
        r2.setDropoffTime("06:00");

        SetForgetRuleRequest r3 = new SetForgetRuleRequest();
        r3.setDayOfWeek((short) 5);
        r3.setServiceType("Daycare After Hours (6 AM - 11 PM)");
        r3.setDropoffTime("06:00");

        SetForgetPlan plan = setForgetService.saveOrUpdatePlan(
                customer,
                true,
                1,
                "SIX_MONTHS",
                List.of(r1, r2, r3)
        );

        return ResponseEntity.ok(Map.of(
                "planId", plan.getId(),
                "active", plan.isActive(),
                "startDate", String.valueOf(plan.getStartDate()),
                "endDate", String.valueOf(plan.getEndDate()),
                "ruleCount", plan.getRules().size()
        ));
    }

    @GetMapping("/generate")
    public ResponseEntity<?> generate(Authentication authentication) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        int created = setForgetService.generateBookingsForActivePlan(customer);

        return ResponseEntity.ok(Map.of(
                "createdCount", created
        ));
    }

    @GetMapping("/clear")
    public ResponseEntity<?> clear(Authentication authentication) {
        User customer = userRepository.findByUsername(authentication.getName()).orElseThrow();

        int deleted = setForgetService.clearFutureGeneratedBookings(customer);

        return ResponseEntity.ok(Map.of(
                "deletedCount", deleted
        ));
    }
}