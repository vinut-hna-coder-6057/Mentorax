-- Harden validated legacy data without altering or deleting existing records.

-- users
ALTER TABLE users
    MODIFY COLUMN password_hash VARCHAR(255) NOT NULL;

-- events
ALTER TABLE events
    MODIFY COLUMN created_by_id BIGINT NOT NULL,
    ADD INDEX idx_events_created_by_id (created_by_id),
    ADD CONSTRAINT fk_events_created_by
        FOREIGN KEY (created_by_id) REFERENCES users (id);

-- event registrations
ALTER TABLE event_registrations
    MODIFY COLUMN event_id BIGINT NOT NULL,
    MODIFY COLUMN user_id BIGINT NOT NULL,
    ADD INDEX idx_event_registrations_event_id (event_id),
    ADD INDEX idx_event_registrations_user_id (user_id),
    ADD CONSTRAINT uk_event_registrations_event_user UNIQUE (event_id, user_id),
    ADD CONSTRAINT fk_event_registrations_event
        FOREIGN KEY (event_id) REFERENCES events (id),
    ADD CONSTRAINT fk_event_registrations_user
        FOREIGN KEY (user_id) REFERENCES users (id);

-- notifications: both relations are nullable in the JPA model, but any value
-- present must refer to an existing user.
ALTER TABLE notifications
    ADD INDEX idx_notifications_recipient_id (recipient_id),
    ADD INDEX idx_notifications_actor_id (actor_id),
    ADD CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_id) REFERENCES users (id),
    ADD CONSTRAINT fk_notifications_actor
        FOREIGN KEY (actor_id) REFERENCES users (id);

-- Messages retain nullable conversation_id for historical direct messages.
ALTER TABLE messages
    ADD INDEX idx_messages_conversation_id (conversation_id),
    ADD INDEX idx_messages_sender_id (sender_id),
    ADD CONSTRAINT fk_messages_sender
        FOREIGN KEY (sender_id) REFERENCES users (id);
