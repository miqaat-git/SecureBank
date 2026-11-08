package com.securebank.transaction.config;

import com.securebank.transaction.workflow.RecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives recovery of incomplete transfers: once at startup (crash recovery) and then periodically.
 * Disabled in tests via securebank.transfer.recovery.enabled=false (tests call RecoveryService directly).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "securebank.transfer.recovery", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final RecoveryService recoveryService;

    public RecoveryScheduler(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        run("startup");
    }

    @Scheduled(initialDelayString = "${securebank.transfer.recovery.fixed-delay-ms:30000}",
            fixedDelayString = "${securebank.transfer.recovery.fixed-delay-ms:30000}")
    public void recoverPeriodically() {
        run("scheduled");
    }

    private void run(String trigger) {
        try {
            int processed = recoveryService.recoverBatch();
            if (processed > 0) {
                log.info("Recovery ({}) processed {} transfer(s)", trigger, processed);
            }
        } catch (RuntimeException e) {
            log.warn("Recovery ({}) failed: {}", trigger, e.getClass().getSimpleName());
        }
    }
}
