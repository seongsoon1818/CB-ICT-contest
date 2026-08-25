ALTER TABLE device_commands ADD COLUMN reason VARCHAR(500);
ALTER TABLE device_commands ADD COLUMN issued_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN executed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN failed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN expired_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN acknowledged_reported_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN executed_reported_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN failed_reported_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN expired_reported_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE device_commands ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE device_commands
SET status = 'EXPIRED',
    reason = 'LEGACY_PRE_MQTT_COMMAND',
    issued_at = created_at,
    expires_at = created_at,
    expired_at = created_at;

ALTER TABLE device_commands ALTER COLUMN reason SET NOT NULL;
ALTER TABLE device_commands ALTER COLUMN issued_at SET NOT NULL;
ALTER TABLE device_commands ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX idx_device_commands_device_created_at
    ON device_commands (device_id, created_at);
