# Template parameter workflow

Execution templates use `TemplateParameterWorkflow` to turn request values into
a validated parameter binding before an execution operator is called.

```text
Execution workflow
  -> TemplateParameterWorkflow
  -> NORMALIZE_REQUEST
  -> COLLECT_EXPLICIT_PARAMETERS
  -> COLLECT_REQUEST_FIELDS
  -> APPLY_DECLARED_DEFAULTS
  -> COERCE_DECLARED_TYPES
  -> VALIDATE_REQUIRED_PARAMETERS
  -> ASSEMBLE_BINDING
  -> execution operator
```

The workflow ID is `resolve.template-parameters.v1`. Parameter precedence is:

1. Values explicitly supplied under `parameters`.
2. Same-name fields from the request envelope.
3. Defaults declared by the template parameter schema.

When a legacy template has no declared schema, explicitly supplied parameters,
or request fields when no explicit parameter map exists, are preserved in
`SCHEMALESS_PASSTHROUGH` mode. The owning execution workflow controls which
request fields are eligible for this fallback. When a schema exists, undeclared
request fields are excluded from the executable binding.

`TemplateParameterValidator` remains the atomic validation operator. It performs
required-field checks, type coercion, enum checks, numeric bounds, string length,
and pattern checks. Domain-specific enrichment such as SQL dynamic dates,
metadata-derived schema names, credentials, and routing context stays in the
owning execution operator.

The resolution contains parameter-source metadata without copying parameter
values into the workflow trace. This allows auditing whether a value came from
explicit parameters, a request field, or a schema default without leaking the
value itself.

`SqlQueryExecutionWorkflow` invokes this workflow before executing registered
business database-query templates and registered SQL templates. Other template
executors can adopt the same workflow without changing its lifecycle or
parameter contract.

`ApiInvokeService` and `HttpRequestToolService` use the same workflow before URL,
header, query-string, and request-body rendering. Session refresh, authentication
material, transport routing, and response evaluation remain API operator rules.
