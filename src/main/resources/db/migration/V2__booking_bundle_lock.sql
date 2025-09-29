-- Adds fields to persist “prepay bundle” state & quoted prices at lock time.

ALTER TABLE booking
    ADD COLUMN IF NOT EXISTS in_prepay_bundle BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS quoted_rate_at_lock NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS bundle_locked_at TIMESTAMPTZ;

-- Speeds up week queries for a user's bookings
CREATE INDEX IF NOT EXISTS idx_booking_user_date ON booking (user_id, date);

-- (Optional) quick filter on bundle state
CREATE INDEX IF NOT EXISTS idx_booking_bundle_flags ON booking (user_id, in_prepay_bundle);
