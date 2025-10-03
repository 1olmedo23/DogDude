-- Enforce: at most one non-canceled booking per user per calendar day
-- Matches app logic (treat NULL status as active)
CREATE UNIQUE INDEX IF NOT EXISTS uniq_booking_active_per_day
    ON public.booking (user_id, date)
    WHERE (COALESCE(UPPER(status), '') <> 'CANCELED');

-- Helpful for status-filtered scans
CREATE INDEX IF NOT EXISTS idx_booking_user_date_status
    ON public.booking (user_id, date, status);
