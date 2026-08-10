-- Disable unchanged fixed seed credentials. The dev-only initializer restores local demo access with BCrypt.

DELETE FROM auth_refresh_token
WHERE user_id IN (
  SELECT id FROM `user`
  WHERE phone IN ('13800138000', '13900139000', '13900139001', '13900139002', '13900139003')
    AND password_hash = 'Abc12345'
);

UPDATE `user`
SET password_hash = '$2a$12$xNRMbC1E8lvXJwYYUNjLSeNaoSHRKb/mvvOCo8hF/82OR.u1wfc/K',
    status = 'disabled',
    token_version = token_version + 1
WHERE phone IN ('13800138000', '13900139000', '13900139001', '13900139002', '13900139003')
  AND password_hash = 'Abc12345';

UPDATE admin_user
SET password_hash = '$2a$12$xNRMbC1E8lvXJwYYUNjLSeNaoSHRKb/mvvOCo8hF/82OR.u1wfc/K',
    status = 'disabled'
WHERE username = 'admin'
  AND password_hash = 'Admin12345';
