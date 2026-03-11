CREATE TABLE IF NOT EXISTS set_forget_exception (
                                                    id BIGSERIAL PRIMARY KEY,
                                                    plan_id BIGINT NOT NULL REFERENCES set_forget_plan(id) ON DELETE CASCADE,
    exception_date DATE NOT NULL,
    reason VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE UNIQUE INDEX IF NOT EXISTS ux_set_forget_exception_plan_date
    ON set_forget_exception(plan_id, exception_date);

CREATE INDEX IF NOT EXISTS ix_set_forget_exception_plan_id
    ON set_forget_exception(plan_id);