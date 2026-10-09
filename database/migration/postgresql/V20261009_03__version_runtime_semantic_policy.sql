-- Version existing published policy without changing its rules or replacing operator edits.
update agent_runtime_semantic_policy
set policy_json = concat('{"schemaVersion":"runtime-semantic-policy.v1","policyVersion":"1",',
    substring(policy_json, 2))
where policy_key = 'default'
  and policy_json not like '%"schemaVersion"%';
