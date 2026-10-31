---
name: python-code-development
description: Design, implement, debug and refactor maintainable Python programs, reusable analysis components and tests with typed interfaces, validation, logging and reliable file handling.
version: 1.0.0
---

# Python code development in Runtime OS

Use for Python engineering, including analysis scripts that need reusable structure, debugging or tests. `python-data-analysis` separately covers analytical methods; use either or both according to the task.

Start from the captured source project and objective. Preserve unrelated files and earlier corrections. Separate data loading, pure transformations, validation, presentation and orchestration. Prefer functions with typed inputs/outputs; introduce classes or dataclasses when they clarify a persistent model or state. Keep configuration explicit and validate required values at boundaries.

Use pathlib and explicit UTF-8 for text files, context managers for resources and atomic replacement where partial writes would corrupt a deliverable. Resolve paths within the task's authorized directories; never hardcode host credentials, database connections or unrelated filesystem paths. Follow the selected Sandbox's existing dependencies and Python version; report a missing dependency rather than installing packages or bypassing isolation.

Catch exceptions where recovery or a useful diagnostic is possible. Preserve exception causes, distinguish malformed input from environmental failures, and avoid broad exception swallowing. Use module loggers and useful levels without printing secrets or complete datasets. Ensure entry points call functions and use `if __name__ == '__main__':` when appropriate.

Write unit tests for nontrivial behavior with ordinary fixtures, missing/invalid inputs and boundary cases. Use stdlib unittest when no test framework is installed. Keep I/O separable so pure transformations can be tested without live services. For debugging, inspect the latest Runtime receipt, fix the actual failing source using the supported repair/project edit interface and retain prior working behavior. Do not regenerate unrelated code or claim tests passed without execution evidence.

Read [references/engineering-checks.md](references/engineering-checks.md) when designing a multi-file program, a test suite or a performance-sensitive refactor.

Runtime OS owns execution: use `workspace_propose` for source-only workspace tasks and `python_execute` for authorized Sandbox tasks. Source-only tasks must not execute code. Deliver complete requested files, tests and any schema-required report through the existing task contract. Compilation, static review, executed tests and business validation are separate claims; describe only checks actually performed.
