ALTER TABLE device_statuses ADD COLUMN operational_status VARCHAR(20);
ALTER TABLE device_statuses ADD COLUMN firmware_version VARCHAR(128);
ALTER TABLE device_statuses ADD COLUMN reported_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_statuses ADD COLUMN received_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_statuses ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE device_statuses
SET operational_status = CASE
        WHEN connected = TRUE THEN 'ONLINE'
        ELSE 'OFFLINE'
    END,
    reported_at = last_seen,
    received_at = COALESCE(last_seen, CURRENT_TIMESTAMP);

ALTER TABLE device_statuses ALTER COLUMN operational_status SET NOT NULL;
ALTER TABLE device_statuses ALTER COLUMN received_at SET NOT NULL;
