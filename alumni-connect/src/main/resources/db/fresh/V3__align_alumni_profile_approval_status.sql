UPDATE alumni_profiles ap
SET approval_status = (
    SELECT u.status
    FROM users u
    WHERE u.id = ap.user_id
)
WHERE EXISTS (
    SELECT 1
    FROM users u
    WHERE u.id = ap.user_id
      AND UPPER(u.role) = 'ALUMNI'
);
