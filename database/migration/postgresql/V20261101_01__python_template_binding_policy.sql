-- Preserve every existing protocol; add only the published Python execution contract.
UPDATE mcp_argument_binding_policy
SET policy_json = jsonb_set(jsonb_set(jsonb_set(policy_json::jsonb,
  '{executionProtocolBindings}', COALESCE(policy_json::jsonb->'executionProtocolBindings', '{}'::jsonb)
    || '{"mcp.python-template.v1":"PARAMETER_EXECUTION"}'::jsonb),
  '{executionValidationFields}', COALESCE(policy_json::jsonb->'executionValidationFields', '{}'::jsonb)
    || '{"PARAMETER_EXECUTION":["code","source","sourcecode","script","command","sql","rawsql","host","url","assetid","environmentid"]}'::jsonb),
  '{runtimeOwnedExecutionInputsByProtocol}', COALESCE(policy_json::jsonb->'runtimeOwnedExecutionInputsByProtocol', '{}'::jsonb)
    || '{"mcp.python-template.v1":["parameters"]}'::jsonb)::text
WHERE policy_key = 'default';
