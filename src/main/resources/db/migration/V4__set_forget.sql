-- === Set & Forget: recurring daycare rules ===

CREATE TABLE IF NOT EXISTS set_forget_plan (
                                               id BIGSERIAL PRIMARY KEY,
                                               user_id BIGINT NOT NULL REFERENCES users(id),

    active BOOLEAN NOT NULL DEFAULT TRUE,

    start_date DATE NOT NULL,
    end_date   DATE NOT NULL, -- "indefinite" stored as start_date + 1 year

    wants_advance_pay BOOLEAN NOT NULL DEFAULT FALSE,
    dog_count INTEGER NOT NULL DEFAULT 1,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

-- One active plan per user (customer)
CREATE UNIQUE INDEX IF NOT EXISTS ux_set_forget_one_active_plan_per_user
    ON set_forget_plan(user_id)
    WHERE active = TRUE;

CREATE TABLE IF NOT EXISTS set_forget_rule (
                                               id BIGSERIAL PRIMARY KEY,
                                               plan_id BIGINT NOT NULL REFERENCES set_forget_plan(id) ON DELETE CASCADE,

    -- 1=Monday ... 7=Sunday (matches java.time.DayOfWeek.getValue())
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),

    -- Must match your existing Booking.serviceType strings
    service_type VARCHAR(255) NOT NULL,

    -- Stored dropoff time, same meaning as booking.time
    dropoff_time TIME NOT NULL,

    active BOOLEAN NOT NULL DEFAULT TRUE
    );

CREATE INDEX IF NOT EXISTS ix_set_forget_rule_plan_id
    ON set_forget_rule(plan_id);

-- Tag generated bookings
ALTER TABLE booking
    ADD COLUMN IF NOT EXISTS set_forget_plan_id BIGINT NULL REFERENCES set_forget_plan(id);