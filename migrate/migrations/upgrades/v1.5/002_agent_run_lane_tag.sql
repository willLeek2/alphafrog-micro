-- Run 在受理时保存泳道标签。主 Beta 与生产请求使用 NULL；历史记录保持 NULL。

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD COLUMN IF NOT EXISTS lane_tag VARCHAR(96);

ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_lane_tag_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_lane_tag_check
        CHECK (lane_tag IS NULL
            OR lane_tag ~ '^[a-z0-9](?:[a-z0-9._-]{0,94}[a-z0-9])?$');

CREATE OR REPLACE FUNCTION alphafrog_reject_agent_run_identity_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.deployment_id IS DISTINCT FROM OLD.deployment_id
        OR NEW.deployment_generation_id IS DISTINCT FROM OLD.deployment_generation_id
        OR NEW.lane_tag IS DISTINCT FROM OLD.lane_tag THEN
        RAISE EXCEPTION 'Agent Run deployment identity and lane tag are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_agent_run_deployment_identity_immutable ON alphafrog_agent_run;

CREATE TRIGGER trg_agent_run_deployment_identity_immutable
    BEFORE UPDATE OF deployment_id, deployment_generation_id, lane_tag
    ON alphafrog_agent_run
    FOR EACH ROW
    EXECUTE FUNCTION alphafrog_reject_agent_run_identity_change();
