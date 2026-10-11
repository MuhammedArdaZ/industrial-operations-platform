CREATE UNIQUE INDEX idx_alerts_open_unique ON alerts (machine_id, rule_type) WHERE status IN ('ACTIVE', 'ACKNOWLEDGED');
