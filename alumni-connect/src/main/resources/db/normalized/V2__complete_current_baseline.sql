-- Complete a database that already applied the original normalized V1.
-- This consolidates the legacy profile backfill and the hardening that old
-- V3/V5/V7 supplied to databases installed through the baseline route.
INSERT INTO student_profiles
    (user_id, roll_number, branch, section, batch_year, college, bio, location,
     verification_status, created_at, updated_at)
SELECT u.id,
       CASE WHEN NULLIF(TRIM(u.rollno), '') IS NOT NULL
                  AND (SELECT COUNT(*) FROM users candidate
                       WHERE UPPER(candidate.role) = 'STUDENT'
                         AND TRIM(candidate.rollno) = TRIM(u.rollno)) = 1
            THEN TRIM(u.rollno) ELSE NULL END,
       u.branch, u.section,
       CASE WHEN TRIM(u.passout_year) REGEXP '^[0-9]{1,4}$'
            THEN CAST(TRIM(u.passout_year) AS UNSIGNED) ELSE NULL END,
       u.college, u.bio, u.location, 'PENDING', NOW(), NOW()
FROM users u
WHERE UPPER(u.role) = 'STUDENT'
  AND NOT EXISTS (SELECT 1 FROM student_profiles sp WHERE sp.user_id = u.id);

INSERT INTO alumni_profiles
    (user_id, graduation_year, branch, college, company, job_title, linkedin_url,
     github_url, bio, location, approval_status, created_at, updated_at)
SELECT u.id,
       CASE WHEN TRIM(u.passout_year) REGEXP '^[0-9]{1,4}$'
            THEN CAST(TRIM(u.passout_year) AS UNSIGNED) ELSE NULL END,
       u.branch, u.college, u.company, u.job_role, u.linkedin, u.github,
       u.bio, u.location, 'PENDING', NOW(), NOW()
FROM users u
WHERE UPPER(u.role) = 'ALUMNI'
  AND NOT EXISTS (SELECT 1 FROM alumni_profiles ap WHERE ap.user_id = u.id);

INSERT IGNORE INTO skills (name)
SELECT DISTINCT TRIM(parts.skill)
FROM users u
JOIN JSON_TABLE(
    CONCAT('["', REPLACE(REPLACE(REPLACE(COALESCE(u.skills, ''), '\\', '\\\\'), '"', '\\"'), ',', '","'), '"]'),
    '$[*]' COLUMNS (skill VARCHAR(255) PATH '$')
) parts
WHERE TRIM(parts.skill) <> '';

INSERT IGNORE INTO user_skills (user_id, skill_id)
SELECT DISTINCT u.id, s.id
FROM users u
JOIN JSON_TABLE(
    CONCAT('["', REPLACE(REPLACE(REPLACE(COALESCE(u.skills, ''), '\\', '\\\\'), '"', '\\"'), ',', '","'), '"]'),
    '$[*]' COLUMNS (skill VARCHAR(255) PATH '$')
) parts
JOIN skills s ON s.name = TRIM(parts.skill)
WHERE TRIM(parts.skill) <> '';

ALTER TABLE conversations
    ADD COLUMN direct_user_low_id BIGINT NULL,
    ADD COLUMN direct_user_high_id BIGINT NULL,
    ADD CONSTRAINT chk_conversations_direct_pair
        CHECK (
            (direct_user_low_id IS NULL AND direct_user_high_id IS NULL)
            OR (direct_user_low_id IS NOT NULL AND direct_user_high_id IS NOT NULL
                AND direct_user_low_id < direct_user_high_id)
        ),
    ADD CONSTRAINT uk_conversations_direct_pair
        UNIQUE (direct_user_low_id, direct_user_high_id),
    ADD INDEX idx_conversations_direct_user_high (direct_user_high_id),
    ADD CONSTRAINT fk_conversations_direct_user_low
        FOREIGN KEY (direct_user_low_id) REFERENCES users (id),
    ADD CONSTRAINT fk_conversations_direct_user_high
        FOREIGN KEY (direct_user_high_id) REFERENCES users (id);

ALTER TABLE connections
    ADD COLUMN pair_low_id BIGINT GENERATED ALWAYS AS (LEAST(requester_id, receiver_id)) STORED,
    ADD COLUMN pair_high_id BIGINT GENERATED ALWAYS AS (GREATEST(requester_id, receiver_id)) STORED;

CREATE UNIQUE INDEX uk_connection_canonical_pair
    ON connections (pair_low_id, pair_high_id);
