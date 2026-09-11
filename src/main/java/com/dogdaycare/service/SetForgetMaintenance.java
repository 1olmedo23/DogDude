package com.dogdaycare.service;

import com.dogdaycare.model.User;
import com.dogdaycare.repository.SetForgetPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(
        name = "app.set-forget.maintenance.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SetForgetMaintenance {

    private static final Logger log =
            LoggerFactory.getLogger(SetForgetMaintenance.class);

    private final SetForgetPlanRepository planRepository;
    private final SetForgetService setForgetService;

    public SetForgetMaintenance(
            SetForgetPlanRepository planRepository,
            SetForgetService setForgetService
    ) {
        this.planRepository = planRepository;
        this.setForgetService = setForgetService;
    }

    @Scheduled(
            initialDelayString =
                    "${app.set-forget.maintenance.initial-delay-ms:60000}",
            fixedDelayString =
                    "${app.set-forget.maintenance.delay-ms:3600000}"
    )
    public void maintainActivePlans() {
        List<User> customers = planRepository.findActivePlanCustomers();
        int created = 0;
        int failed = 0;

        for (User customer : customers) {
            try {
                // Each customer is processed in a separate transaction.
                created += setForgetService
                        .generateBookingsForActivePlan(customer);
            } catch (RuntimeException ex) {
                failed++;
                log.error(
                        "Set & Forget check failed for customer {}; will retry next pass",
                        customer.getId(),
                        ex
                );
            }
        }

        log.info(
                "Set & Forget check complete: {} plans checked, {} bookings created, {} failed",
                customers.size(),
                created,
                failed
        );
    }
}