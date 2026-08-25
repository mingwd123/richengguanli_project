ALTER TABLE `user`
  ADD COLUMN profile_version BIGINT NOT NULL DEFAULT 0 AFTER token_version;
