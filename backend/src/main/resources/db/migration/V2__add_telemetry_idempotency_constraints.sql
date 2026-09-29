ALTER TABLE telemetry
    ADD CONSTRAINT uq_telemetry_sensor_source UNIQUE (sensor_id, source_message_id);

ALTER TABLE telemetry
    ADD CONSTRAINT uq_telemetry_event_id UNIQUE (event_id);
