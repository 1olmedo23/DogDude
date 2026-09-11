-- Existing plans remain non-renewing until their original choice is confirmed.
ALTER TABLE set_forget_plan
    ADD COLUMN IF NOT EXISTS auto_renew BOOLEAN NOT NULL DEFAULT FALSE;