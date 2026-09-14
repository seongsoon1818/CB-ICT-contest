ALTER TABLE device_commands ADD COLUMN command_source VARCHAR(20);

UPDATE device_commands
SET command_source = 'AUTOMATIC';

ALTER TABLE device_commands ALTER COLUMN command_source SET NOT NULL;
ALTER TABLE device_commands ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE device_commands ALTER COLUMN duration_ms DROP NOT NULL;
