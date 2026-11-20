package com.industrialoperations.platform.alert;

import java.util.UUID;

public class AlertConcurrentModificationException extends RuntimeException {

    private final UUID alertId;

    public AlertConcurrentModificationException(UUID alertId, Exception cause) {
        this.alertId = alertId;
        super("Version conflict before commit on alertId= " + alertId, cause);
    }
}
