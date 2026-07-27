-- Repair demo user nicknames that were previously stored with an incorrect character encoding.
UPDATE `user`
SET nickname = CASE phone
  WHEN '13800138000' THEN 'Test Owner A'
  WHEN '13900139000' THEN 'Test Member B'
  WHEN '13900139001' THEN 'Test Member C'
  WHEN '13900139002' THEN 'Test Member D'
  WHEN '13900139003' THEN 'Test Member E'
END
WHERE phone IN ('13800138000', '13900139000', '13900139001', '13900139002', '13900139003');
