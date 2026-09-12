CREATE TABLE machines (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    external_reference VARCHAR(255) UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE sensors (
    sensor_id VARCHAR(100) PRIMARY KEY,
    machine_id UUID NOT NULL REFERENCES machines (id),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE telemetry (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    source_message_id VARCHAR(255) NOT NULL,
    sensor_id VARCHAR(100) NOT NULL REFERENCES sensors (sensor_id),
    machine_id UUID NOT NULL REFERENCES machines (id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    temperature NUMERIC NOT NULL,
    vibration NUMERIC NOT NULL
);

CREATE TABLE machine_latest_state (
    machine_id UUID PRIMARY KEY REFERENCES machines (id),
    telemetry_id UUID NOT NULL REFERENCES telemetry (id),
    event_id UUID NOT NULL,
    source_message_id VARCHAR(255) NOT NULL,
    sensor_id VARCHAR(100) NOT NULL REFERENCES sensors (sensor_id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    temperature NUMERIC NOT NULL,
    vibration NUMERIC NOT NULL
);
