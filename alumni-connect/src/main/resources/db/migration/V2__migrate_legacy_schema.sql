-- Preserve the pre-Flyway schema and bring it in line with the JPA model.
ALTER TABLE users RENAME COLUMN password TO password_hash;

ALTER TABLE events RENAME COLUMN created_by TO created_by_email;
ALTER TABLE events ADD COLUMN created_by_id BIGINT NULL;
UPDATE events e JOIN users u ON u.email = e.created_by_email
SET e.created_by_id = u.id;

ALTER TABLE event_registrations ADD COLUMN user_id BIGINT NULL;
UPDATE event_registrations er JOIN users u ON u.email = er.student_email
SET er.user_id = u.id;

ALTER TABLE messages ADD COLUMN conversation_id BIGINT NULL;
ALTER TABLE messages ADD COLUMN sender_id BIGINT NULL;
UPDATE messages m JOIN users u ON u.email = m.sender_email
SET m.sender_id = u.id;

ALTER TABLE notifications ADD COLUMN recipient_id BIGINT NULL;
ALTER TABLE notifications ADD COLUMN actor_id BIGINT NULL;
UPDATE notifications n JOIN users u ON u.email = n.email
SET n.recipient_id = u.id;

CREATE TABLE student_profiles (
    user_id BIGINT NOT NULL,
    roll_number VARCHAR(255),
    branch VARCHAR(255),
    section VARCHAR(255),
    batch_year INT,
    college VARCHAR(255),
    bio VARCHAR(1000),
    location VARCHAR(255),
    verification_status VARCHAR(32),
    verified_by BIGINT,
    verified_at DATETIME,
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (user_id),
    CONSTRAINT uk_student_roll_number UNIQUE (roll_number),
    CONSTRAINT fk_student_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE alumni_profiles (
    user_id BIGINT NOT NULL,
    graduation_year INT,
    branch VARCHAR(255),
    college VARCHAR(255),
    company VARCHAR(255),
    job_title VARCHAR(255),
    linkedin_url VARCHAR(255),
    github_url VARCHAR(255),
    bio VARCHAR(1000),
    location VARCHAR(255),
    approval_status VARCHAR(32),
    approved_by BIGINT,
    approved_at DATETIME,
    created_at DATETIME,
    updated_at DATETIME,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_alumni_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE skills (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_skills_name UNIQUE (name)
);

CREATE TABLE user_skills (
    user_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    proficiency VARCHAR(64),
    PRIMARY KEY (user_id, skill_id),
    CONSTRAINT fk_user_skills_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_skills_skill FOREIGN KEY (skill_id) REFERENCES skills (id)
);

CREATE TABLE connections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    requester_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME,
    responded_at DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_connection_direction UNIQUE (requester_id, receiver_id),
    CONSTRAINT fk_connection_requester FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_connection_receiver FOREIGN KEY (receiver_id) REFERENCES users (id)
);

CREATE TABLE conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME,
    updated_at DATETIME,
    last_message_at DATETIME,
    PRIMARY KEY (id)
);

CREATE TABLE conversation_participants (
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_participant_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_participant_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE otp_verifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT,
    email VARCHAR(255) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    purpose VARCHAR(64) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    consumed_at DATETIME,
    expiry DATETIME,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_otp_user FOREIGN KEY (user_id) REFERENCES users (id)
);
