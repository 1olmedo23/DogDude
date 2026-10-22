
CREATE TABLE public.employee_time_entry (
                                            id BIGSERIAL PRIMARY KEY,

                                            employee_id BIGINT NOT NULL
                                                REFERENCES public.users(id),

                                            clock_in TIMESTAMP NOT NULL,

                                            clock_out TIMESTAMP,

                                            CONSTRAINT employee_time_entry_valid_times
                                                CHECK (
                                                    clock_out IS NULL
                                                        OR clock_out >= clock_in
                                                    )
);

CREATE INDEX idx_employee_time_entry_employee_clock_in
    ON public.employee_time_entry (employee_id, clock_in);

-- Prevent an employee from having multiple open clock-ins.
CREATE UNIQUE INDEX uniq_employee_active_clock_in
    ON public.employee_time_entry (employee_id)
    WHERE clock_out IS NULL;
