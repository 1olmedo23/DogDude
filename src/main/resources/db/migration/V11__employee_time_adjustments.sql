-- Preserve original employee clock-in/out timestamps.
-- Store admin adjustments separately.

ALTER TABLE public.employee_time_entry
    ADD COLUMN adjusted_clock_in TIMESTAMP,
    ADD COLUMN adjusted_clock_out TIMESTAMP;