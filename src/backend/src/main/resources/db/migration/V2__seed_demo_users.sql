-- Seed demo users for local MVP verification.
-- DbStore accepts this plaintext fallback only for existing demo rows; real registrations use BCrypt.

INSERT INTO `user` (phone, password_hash, nickname, avatar_url, timezone, status)
SELECT '13800138000', 'Abc12345', '演示用户A', '', 'Asia/Shanghai', 'active'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE phone = '13800138000');

INSERT INTO `user` (phone, password_hash, nickname, avatar_url, timezone, status)
SELECT '13900139000', 'Abc12345', '演示用户B', '', 'Asia/Shanghai', 'active'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE phone = '13900139000');