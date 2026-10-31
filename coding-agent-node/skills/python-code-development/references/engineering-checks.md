# Engineering checks

For a reusable component, provide a small public API with type annotations and docstrings that explain units, preconditions, return values and exceptions. Use explicit function parameters or a dataclass for configuration; read environment variables only at a defined application boundary. Avoid global mutable analysis state.

An ordinary layout is `loader.py` (file parsing), `analysis.py` (pure calculations), `report.py` (serialization/charts), `main.py` (orchestration) and `test_analysis.py`. Adapt the layout to the allowed file contract; a single file can still contain separately testable functions. Never force a package layout when Runtime accepts a single script or only .py proposals.

Tests should verify outputs rather than mirror the implementation: valid input, empty input, missing required columns, duplicate keys, malformed dates, zero denominators, precision-sensitive values and incorrect types where relevant. Use temporary directories for file tests and assert specific exception types. For Decimal computations compare exact expected values; use tolerances only for approximate floating-point calculations. Synthetic fixtures test code, not business claims.

Keep dependency declarations consistent with the existing project. Prefer the standard library when it meets the requirement. Use existing lint/type tools if available; do not alter the Sandbox or install tools to make a check pass. Avoid mutable default arguments, implicit encoding, ambiguous datetime handling and unsafe deserialization.

For performance work measure a representative bounded workload first. Reduce repeated I/O, select columns, batch large files and vectorize where equivalent. Recheck numerical results, row cardinality and missing-value handling after optimization. A faster transformation with changed semantics is a regression.
