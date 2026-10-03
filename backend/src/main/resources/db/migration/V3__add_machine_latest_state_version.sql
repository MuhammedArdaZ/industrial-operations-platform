ALTER TABLE machine_latest_state
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
