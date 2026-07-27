-- Seed members for the local MVP demo team owned by demo account 13800138000.
-- All demo accounts use password Abc12345; normal registration continues to use BCrypt.

INSERT INTO `user` (phone, password_hash, nickname, avatar_url, timezone, status)
SELECT '13900139000', 'Abc12345', 'Test Member B', '', 'Asia/Shanghai', 'active'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE phone = '13900139000');

INSERT INTO `user` (phone, password_hash, nickname, avatar_url, timezone, status)
SELECT '13900139001', 'Abc12345', 'Test Member C', '', 'Asia/Shanghai', 'active'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE phone = '13900139001');

INSERT INTO `user` (phone, password_hash, nickname, avatar_url, timezone, status)
SELECT '13900139002', 'Abc12345', 'Test Member D', '', 'Asia/Shanghai', 'active'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE phone = '13900139002');

INSERT INTO `user` (phone, password_hash, nickname, avatar_url, timezone, status)
SELECT '13900139003', 'Abc12345', 'Test Member E', '', 'Asia/Shanghai', 'active'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE phone = '13900139003');

INSERT INTO team_member (team_id, user_id, role, status, joined_at)
SELECT t.id, u.id, CASE u.phone WHEN '13900139001' THEN 'admin' ELSE 'member' END, 'active', utc_timestamp()
FROM team t
JOIN `user` owner ON owner.id = t.owner_id AND owner.phone = '13800138000'
JOIN `user` u ON u.phone IN ('13900139000', '13900139001', '13900139002', '13900139003')
LEFT JOIN team_member tm ON tm.team_id = t.id AND tm.user_id = u.id
WHERE t.deleted_at IS NULL AND tm.id IS NULL;
