UPDATE alumni_profiles ap
JOIN users u ON u.id = ap.user_id
SET ap.approval_status = u.status
WHERE UPPER(u.role) = 'ALUMNI';
