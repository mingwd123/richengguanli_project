ALTER TABLE ai_api_key
  ADD COLUMN api_base_url VARCHAR(500) NULL AFTER api_key_ciphertext;
