SET search_path = main;

ALTER TABLE transaction
    ADD COLUMN IF NOT EXISTS pay_token VARCHAR(255),
    ADD COLUMN IF NOT EXISTS pay_token_issued_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS poll_attempts INTEGER DEFAULT 0;
