ALTER TABLE events
    ADD COLUMN max_capacity INT NULL;

ALTER TABLE events
    ADD CONSTRAINT chk_event_capacity
        CHECK (
            max_capacity IS NULL
            OR (max_capacity > 0 AND attendee_count <= max_capacity)
        );
