-- MethodSpec V5: resolver snapshots and finance record-channel audit/persistence.

CREATE TABLE IF NOT EXISTS alphafrog_agent_finance_method_resolution (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    resolver_tool_call_id VARCHAR(160) NOT NULL,
    todo_id VARCHAR(160) NOT NULL,
    method_id VARCHAR(160) NOT NULL,
    method_version VARCHAR(64) NOT NULL,
    spec_digest VARCHAR(160) NOT NULL,
    catalog_digest VARCHAR(160) NOT NULL,
    resolver_schema_version VARCHAR(64) NOT NULL,
    resolver_prompt_version VARCHAR(64) NOT NULL,
    model_route_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    match_reason TEXT NOT NULL,
    clarification_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_environment_id VARCHAR(256),
    target_package_api_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    resolution_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    resolution_content_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, resolver_tool_call_id, method_id, method_version, spec_digest)
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_finance_record_batch (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    todo_id VARCHAR(160) NOT NULL,
    execute_python_tool_call_id VARCHAR(160) NOT NULL,
    entry_point VARCHAR(32) NOT NULL,
    terminal_status VARCHAR(32) NOT NULL,
    exit_code INT NOT NULL,
    record_count INT NOT NULL,
    record_bytes BIGINT NOT NULL,
    record_digest VARCHAR(64) NOT NULL,
    record_set_complete BOOLEAN NOT NULL,
    drop_reason TEXT NOT NULL DEFAULT '',
    schema_valid BOOLEAN NOT NULL,
    renderable BOOLEAN NOT NULL,
    actual_environment_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_error_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    batch_content_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, todo_id, execute_python_tool_call_id)
);

CREATE TABLE IF NOT EXISTS alphafrog_agent_finance_record (
    id BIGSERIAL PRIMARY KEY,
    record_id VARCHAR(64) NOT NULL UNIQUE,
    run_id VARCHAR(64) NOT NULL,
    todo_id VARCHAR(160) NOT NULL,
    execute_python_tool_call_id VARCHAR(160) NOT NULL,
    record_index INT NOT NULL,
    raw_digest VARCHAR(64) NOT NULL,
    raw_payload TEXT NOT NULL,
    source_resolver_tool_call_id VARCHAR(160),
    method_id VARCHAR(160),
    method_version VARCHAR(64),
    spec_digest VARCHAR(160),
    value_json JSONB,
    unit VARCHAR(96),
    parameters_json JSONB,
    input_refs_json JSONB,
    checks_json JSONB,
    formula_description TEXT,
    declared_evidence VARCHAR(32) NOT NULL,
    effective_internal_evidence VARCHAR(32) NOT NULL,
    actual_environment_id VARCHAR(256),
    renderable BOOLEAN NOT NULL,
    validation_error_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, todo_id, execute_python_tool_call_id, record_index, raw_digest)
);

CREATE INDEX IF NOT EXISTS idx_finance_resolution_run_created
    ON alphafrog_agent_finance_method_resolution(run_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_finance_record_batch_run_created
    ON alphafrog_agent_finance_record_batch(run_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_finance_record_run_renderable_created
    ON alphafrog_agent_finance_record(run_id, renderable, created_at, id);
