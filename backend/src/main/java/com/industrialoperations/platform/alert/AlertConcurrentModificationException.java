package com.industrialoperations.platform.alert;

import java.util.UUID;

public class AlertConcurrentModificationException extends RuntimeException {

    private final UUID alertId;

    public AlertConcurrentModificationException(UUID alertId, Exception cause) {
        this.alertId = alertId;
        super("Acknowledge failed due to concurrent modification, alertId=" + alertId, cause);
    }

    public UUID getAlertId() {
        return alertId;
    }

}
