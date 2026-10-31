-- Preserve other protocol entries; quoted JSON paths protect dots in protocol families.
UPDATE mcp_argument_binding_policy
SET policy_json = JSON_SET(policy_json,
  '$.executionProtocolBindings."mcp.python-template.v1"', 'PARAMETER_EXECUTION',
  '$.executionValidationFields.PARAMETER_EXECUTION', JSON_ARRAY('code','source','sourcecode','script','command','sql','rawsql','host','url','assetid','environmentid'),
  '$.runtimeOwnedExecutionInputsByProtocol."mcp.python-template.v1"', JSON_ARRAY('parameters'))
WHERE policy_key = 'default';
