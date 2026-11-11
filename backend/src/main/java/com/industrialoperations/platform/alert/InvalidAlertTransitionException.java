package com.industrialoperations.platform.alert;

import java.util.UUID;

public class InvalidAlertTransitionException extends RuntimeException {

    private final AlertStatus currentStatus;

    public InvalidAlertTransitionException(UUID alertId, AlertStatus currentStatus){
        this.currentStatus = currentStatus;
        super("Invalid transition for alert " + alertId + ": current status is " + currentStatus);
    }

    public AlertStatus getCurrentStatus() {
        return currentStatus;
    }

}
