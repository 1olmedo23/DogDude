ALTER TABLE booking
    ADD COLUMN IF NOT EXISTS manual_adjustment_amount NUMERIC(10,2);

ALTER TABLE booking
    ADD COLUMN IF NOT EXISTS manual_adjustment_reason VARCHAR(120);

ALTER TABLE booking
    ADD COLUMN IF NOT EXISTS manual_adjustment_updated_at TIMESTAMP;