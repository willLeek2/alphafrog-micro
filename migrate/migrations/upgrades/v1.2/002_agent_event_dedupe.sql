ALTER TABLE alphafrog_agent_run_event
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_event_logical_dedupe
    ON alphafrog_agent_run_event (run_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;
