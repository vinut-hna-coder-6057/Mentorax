ALTER TABLE knowledge_contributions
    ADD COLUMN freshness_interval_days SMALLINT NOT NULL DEFAULT 180;
ALTER TABLE knowledge_contributions
    ADD COLUMN freshness_reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE knowledge_contributions ADD COLUMN last_confirmed_at DATETIME(6) NULL;
ALTER TABLE knowledge_contributions ADD COLUMN reminder_due_at DATETIME(6) NULL;
ALTER TABLE knowledge_contributions ADD COLUMN last_reminder_sent_at DATETIME(6) NULL;
CREATE INDEX idx_knowledge_freshness_due
    ON knowledge_contributions (freshness_reminders_enabled, reminder_due_at, last_reminder_sent_at);

CREATE TABLE knowledge_companion_conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_conversation_student FOREIGN KEY (student_user_id) REFERENCES users(id),
    INDEX idx_knowledge_conversation_owner_updated (student_user_id, updated_at)
);

CREATE TABLE knowledge_companion_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    speaker VARCHAR(12) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    evidence_status VARCHAR(32) NULL,
    answer_mode VARCHAR(40) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_message_conversation FOREIGN KEY (conversation_id) REFERENCES knowledge_companion_conversations(id),
    INDEX idx_knowledge_messages_conversation_created (conversation_id, created_at, id)
);

CREATE TABLE knowledge_companion_message_sources (
    message_id BIGINT NOT NULL,
    contribution_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    relevance_score DECIMAL(5,4) NOT NULL,
    PRIMARY KEY (message_id, contribution_id, version_number),
    CONSTRAINT fk_knowledge_message_source_message FOREIGN KEY (message_id) REFERENCES knowledge_companion_messages(id),
    CONSTRAINT fk_knowledge_message_source_version FOREIGN KEY (contribution_id, version_number)
        REFERENCES knowledge_contribution_versions(contribution_id, version_number),
    INDEX idx_knowledge_message_source_contribution (contribution_id, version_number)
);

CREATE TABLE knowledge_question_interest (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_user_id BIGINT NOT NULL,
    topic_hash CHAR(64) NOT NULL,
    topic_label VARCHAR(120) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_question_student FOREIGN KEY (student_user_id) REFERENCES users(id),
    CONSTRAINT uk_knowledge_question_student_topic UNIQUE (student_user_id, topic_hash),
    INDEX idx_knowledge_question_topic (topic_hash, topic_label)
);

CREATE TABLE knowledge_roadmaps (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_user_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    goal VARCHAR(500) NOT NULL,
    evidence_status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_roadmap_student FOREIGN KEY (student_user_id) REFERENCES users(id),
    INDEX idx_knowledge_roadmap_owner_updated (student_user_id, updated_at)
);

CREATE TABLE knowledge_roadmap_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    roadmap_id BIGINT NOT NULL,
    position_index SMALLINT NOT NULL,
    phase VARCHAR(80) NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    source_kind VARCHAR(24) NOT NULL,
    progress_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_roadmap_item_roadmap FOREIGN KEY (roadmap_id) REFERENCES knowledge_roadmaps(id),
    CONSTRAINT uk_knowledge_roadmap_item_position UNIQUE (roadmap_id, position_index),
    INDEX idx_knowledge_roadmap_items (roadmap_id, position_index)
);

CREATE TABLE knowledge_roadmap_item_sources (
    item_id BIGINT NOT NULL,
    contribution_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    PRIMARY KEY (item_id, contribution_id, version_number),
    CONSTRAINT fk_knowledge_roadmap_source_item FOREIGN KEY (item_id) REFERENCES knowledge_roadmap_items(id),
    CONSTRAINT fk_knowledge_roadmap_source_version FOREIGN KEY (contribution_id, version_number)
        REFERENCES knowledge_contribution_versions(contribution_id, version_number),
    INDEX idx_knowledge_roadmap_source_contribution (contribution_id, version_number)
);
