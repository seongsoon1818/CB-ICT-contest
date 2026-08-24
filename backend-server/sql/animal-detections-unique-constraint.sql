-- Apply once to an existing non-local PostgreSQL schema before starting with ddl-auto=validate.
-- The statement fails if duplicate (event_id, detection_id) rows or the named constraint already exist.
ALTER TABLE animal_detections
    ADD CONSTRAINT uk_animal_detections_event_detection
    UNIQUE (event_id, detection_id);
