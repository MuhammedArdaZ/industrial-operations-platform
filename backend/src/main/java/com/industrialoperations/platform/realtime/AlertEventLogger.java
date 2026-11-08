package com.industrialoperations.platform.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.industrialoperations.platform.alert.AlertEvent;

@Component
public class AlertEventLogger {
    private static final Logger log = LoggerFactory.getLogger(AlertEventLogger.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AlertEvent event) {
        log.info("type={}, alertId={}, machineId={}, status={}, version={}",
                event.eventType(), event.alertId(), event.machineId(), event.status(), event.version());
    }
}
