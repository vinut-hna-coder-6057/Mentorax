-- Preserve legacy messages: conversation_id remains nullable for historical rows.
-- Canonical user ordering makes each newly-created direct conversation unique.
ALTER TABLE conversations
    ADD COLUMN direct_user_low_id BIGINT NULL,
    ADD COLUMN direct_user_high_id BIGINT NULL,
    ADD CONSTRAINT chk_conversations_direct_pair
        CHECK (
            (direct_user_low_id IS NULL AND direct_user_high_id IS NULL)
            OR (
                direct_user_low_id IS NOT NULL
                AND direct_user_high_id IS NOT NULL
                AND direct_user_low_id < direct_user_high_id
            )
        ),
    ADD CONSTRAINT uk_conversations_direct_pair
        UNIQUE (direct_user_low_id, direct_user_high_id),
    ADD INDEX idx_conversations_direct_user_high (direct_user_high_id),
    ADD CONSTRAINT fk_conversations_direct_user_low
        FOREIGN KEY (direct_user_low_id) REFERENCES users (id),
    ADD CONSTRAINT fk_conversations_direct_user_high
        FOREIGN KEY (direct_user_high_id) REFERENCES users (id);

-- Existing historical messages retain NULL conversation_id and remain valid.
ALTER TABLE messages
    ADD CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id);
