# Embedding dimension compatibility

`dimension` and `dimension-request-mode` have different responsibilities:

- `dimension` is the expected vector length and the OpenSearch `knn_vector` mapping dimension.
- `dimension-request-mode` controls whether the OpenAI-compatible embedding request contains
  the optional `dimensions` field.

Supported modes:

- `AUTO` (default): send `dimensions` first. If the endpoint returns HTTP 400 or 422, retry once
  without it and remember that capability for the endpoint/model until restart.
- `ALWAYS`: always send `dimensions` and do not perform compatibility fallback.
- `NEVER`: never send `dimensions`; use the model's native output dimension.

For a model with a fixed native size of 2560 that rejects the optional request field:

```yaml
embedding:
  dimension: 2560
  dimension-request-mode: NEVER
```

Alternatively, keep `AUTO` to detect the endpoint behavior automatically. The returned vector is
always checked against `dimension`. A model that returns a different length is not written to the
index because mixing vector sizes would make the OpenSearch mapping invalid. Rebuild an existing
vector index after changing `dimension`.

Environment overrides:

- API: `CHATCHAT_SEARCH_EMBEDDING_DIMENSION_REQUEST_MODE`
- MCP Server: `CHATCHAT_MCP_EMBEDDING_DIMENSION_REQUEST_MODE`
