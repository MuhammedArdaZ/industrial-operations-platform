package com.industrialoperations.platform.common;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Measured values carried by a single telemetry observation. Telemetry history and the
 * latest-state projection store the same measurement set by definition, so both own this type.
 */
public record Measurements(BigDecimal temperature, BigDecimal vibration) {

    public Measurements {
        Objects.requireNonNull(temperature, "temperature cannot be null");
        Objects.requireNonNull(vibration, "vibration cannot be null");
    }
}
