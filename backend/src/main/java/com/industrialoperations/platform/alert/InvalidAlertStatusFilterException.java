package com.industrialoperations.platform.alert;

import java.util.List;

/**
 * InvalidAlertStatusFilterException thrown when request contains outside the
 * allowed set.
 */
public class InvalidAlertStatusFilterException extends RuntimeException {

    private final List<AlertStatus> rejected;
    private final List<AlertStatus> allowed;

    public InvalidAlertStatusFilterException(List<AlertStatus> rejected, List<AlertStatus> allowed) {
        super("Requested statuses NOT allowed=" + rejected + ", allowed=" + allowed);
        this.rejected = List.copyOf(rejected);
        this.allowed = List.copyOf(allowed);
    }

    public InvalidAlertStatusFilterException(List<AlertStatus> allowed) {
        super("Status must not contain empty values, allowed=" + allowed);
        this.rejected = List.of();
        this.allowed = List.copyOf(allowed);
    }

    public List<AlertStatus> getRejected() {
        return rejected;
    }

    public List<AlertStatus> getAllowed() {
        return allowed;
    }

}
