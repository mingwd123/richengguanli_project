-- Persist the user's AI data-recording preference on the server so it cannot
-- be bypassed by changing a client-side flag or using another device.
ALTER TABLE `user`
  ADD COLUMN ai_record_enabled BOOLEAN NOT NULL DEFAULT TRUE;
