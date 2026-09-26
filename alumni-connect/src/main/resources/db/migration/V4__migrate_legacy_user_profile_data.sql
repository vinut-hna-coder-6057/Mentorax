-- Copy legacy fields to normalized tables; legacy columns remain for compatibility.
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

-- MySQL 8 JSON_TABLE expands comma-separated legacy skills. Empty values are ignored.
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
