ALTER TABLE ai_config
  ADD COLUMN api_key_ciphertext TEXT NULL AFTER api_key_masked;
