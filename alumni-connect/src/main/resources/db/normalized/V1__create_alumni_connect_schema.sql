CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255),
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32),
    status VARCHAR(32),
    college VARCHAR(255),
    passout_year VARCHAR(32),
    rollno VARCHAR(255),
    section VARCHAR(255),
    branch VARCHAR(255),
    bio VARCHAR(1000),
    skills VARCHAR(255),
    company VARCHAR(255),
    job_role VARCHAR(255),
    linkedin VARCHAR(255),
    github VARCHAR(255),
    profile_image VARCHAR(255),
    interests VARCHAR(255),
    location VARCHAR(255),
    created_at DATETIME,
    updated_at DATETIME,
    last_login_at DATETIME,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    account_status VARCHAR(32),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_status ON users (status);
CREATE INDEX idx_users_account_status ON users (account_status);

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
    CONSTRAINT fk_student_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_student_verified_by FOREIGN KEY (verified_by) REFERENCES users (id)
);

CREATE INDEX idx_student_branch ON student_profiles (branch);
CREATE INDEX idx_student_batch_year ON student_profiles (batch_year);

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
    CONSTRAINT fk_alumni_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_alumni_approved_by FOREIGN KEY (approved_by) REFERENCES users (id)
);

CREATE INDEX idx_alumni_company ON alumni_profiles (company);
CREATE INDEX idx_alumni_job_title ON alumni_profiles (job_title);
CREATE INDEX idx_alumni_approval_status ON alumni_profiles (approval_status);
CREATE INDEX idx_alumni_graduation_year ON alumni_profiles (graduation_year);

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

CREATE TABLE events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(5000),
    location VARCHAR(255),
    event_date VARCHAR(255),
    created_by_email VARCHAR(255),
    created_by_id BIGINT,
    role VARCHAR(32),
    status VARCHAR(32),
    attendee_count INT NOT NULL DEFAULT 0,
    category VARCHAR(255),
    meeting_link VARCHAR(2000),
    image_url VARCHAR(3000),
    created_at DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_events_creator FOREIGN KEY (created_by_id) REFERENCES users (id)
);

CREATE INDEX idx_events_date ON events (event_date);
CREATE INDEX idx_events_status ON events (status);
CREATE INDEX idx_events_category ON events (category);
CREATE INDEX idx_events_creator ON events (created_by_id);

CREATE TABLE event_registrations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    student_email VARCHAR(255),
    registered_at DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_event_registration_legacy UNIQUE (event_id, student_email),
    CONSTRAINT uk_event_registration_user UNIQUE (event_id, user_id),
    CONSTRAINT fk_registration_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_registration_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_registration_event ON event_registrations (event_id);
CREATE INDEX idx_registration_user ON event_registrations (user_id);

CREATE TABLE connections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    requester_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME,
    responded_at DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_connection_direction UNIQUE (requester_id, receiver_id),
    CONSTRAINT chk_connection_not_self CHECK (requester_id <> receiver_id),
    CONSTRAINT fk_connection_requester FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_connection_receiver FOREIGN KEY (receiver_id) REFERENCES users (id)
);

CREATE INDEX idx_connections_receiver ON connections (receiver_id);
CREATE INDEX idx_connections_status ON connections (status);

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

CREATE INDEX idx_participant_user ON conversation_participants (user_id);

CREATE TABLE messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT,
    sender_id BIGINT,
    sender_email VARCHAR(255),
    receiver_email VARCHAR(255),
    content VARCHAR(2000),
    timestamp DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES users (id)
);

CREATE INDEX idx_messages_conversation ON messages (conversation_id);
CREATE INDEX idx_messages_sender ON messages (sender_id);
CREATE INDEX idx_messages_timestamp ON messages (timestamp);

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient_id BIGINT,
    actor_id BIGINT,
    email VARCHAR(255),
    message VARCHAR(1000),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    type VARCHAR(64),
    link_url VARCHAR(1000),
    timestamp DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT fk_notification_actor FOREIGN KEY (actor_id) REFERENCES users (id)
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_id);
CREATE INDEX idx_notifications_read ON notifications (is_read);
CREATE INDEX idx_notifications_timestamp ON notifications (timestamp);

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

CREATE INDEX idx_otp_email ON otp_verifications (email);
CREATE INDEX idx_otp_expiry ON otp_verifications (expiry);
CREATE INDEX idx_otp_purpose ON otp_verifications (purpose);
