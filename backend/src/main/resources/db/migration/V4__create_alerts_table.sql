CREATE TABLE alerts (
    id UUID PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES machines (id),
    sensor_id VARCHAR(100) NOT NULL REFERENCES sensors (sensor_id),
    rule_type VARCHAR(50) NOT NULL,
    severity VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    current_value NUMERIC NOT NULL,
    threshold_value NUMERIC NOT NULL,
    message VARCHAR(500) NOT NULL,
    triggered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_alerts_machine_status ON alerts (machine_id, status);
CREATE INDEX idx_alerts_status ON alerts (status);
