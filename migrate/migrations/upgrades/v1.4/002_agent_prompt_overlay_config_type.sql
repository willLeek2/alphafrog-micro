-- 登记 Prompt 运行时覆盖文档的配置类型。
-- 这份文档走独立 dataId，不和 agent-llm 整份配置混在一起。

INSERT INTO alphafrog_config_type (name, data_id, config_group, service_name, schema_json, description)
VALUES (
    'agent-prompt-overlay',
    'agent-prompt-overlay.json',
    'alphafrog-config',
    'agent-service',
    '{
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "additionalProperties": false,
        "required": ["formatVersion"],
        "properties": {
            "formatVersion": {"type": "integer", "enum": [1]},
            "baseBundleDigest": {"type": "string"},
            "prompts": {"type": "object", "additionalProperties": {"type": "string"}},
            "toolDescriptions": {"type": "object", "additionalProperties": {"type": "string"}}
        }
    }',
    'Prompt 运行时覆盖文档（Nacos dataId agent-prompt-overlay.json）'
)
ON CONFLICT (name) DO UPDATE SET
    data_id = EXCLUDED.data_id,
    config_group = EXCLUDED.config_group,
    service_name = EXCLUDED.service_name,
    schema_json = EXCLUDED.schema_json,
    description = EXCLUDED.description;
