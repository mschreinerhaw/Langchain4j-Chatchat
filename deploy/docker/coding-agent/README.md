# Runtime Coding v1 deployment

The main analysis model can request `RUN_CODING` after the existing authorized
acquisition workflow has delivered a dataset. Runtime exports that dataset as an
immutable, scoped snapshot, freezes the contract and budget, dispatches the worker,
and registers the verified execution artifacts. The worker does not discover or
execute data acquisition templates.

Build from the repository root with
`docker build -t runtime-coding-agent:1.0 coding-agent-node`, then start the
configured image with `docker compose -f deploy/docker/coding-agent/compose.yml up -d --no-build`.
Its host port binds only to loopback. The image has no Docker socket, business
database mounts or user upload mounts; its tools are the contract-bound
`python_execute`, `metadata_read`, and `artifact_read` callbacks.

Import `runtime-coding.properties.example` from the API's external Spring
configuration after selecting a real published Python environment with network
policy `NONE`. Provision a random shared secret of at least 32 characters as a
file, readable by Java and mounted read-only for the container's group `32101`.
Production and staging must use different secrets. Do not configure both inline
and file secret properties.

Use `compose.yml` for the worker. Install `install-network-boundary.sh` on its
Docker host; the dedicated gateway sends production container `.10` to Java 8080
and staging container `.11` to Java 18080. `CODING_JAVA_PORT` and
`CODING_STAGE_JAVA_PORT` override these deployment ports. Run
`verify-network-boundary.mjs` inside each container while its Java service is
running. A successful check requires unsigned callbacks to reach Java with 401,
general API paths to return 404, and all tested direct destinations to be denied.
Recheck the boundary after Docker, firewall or host network changes.

Upgrade Java and Node together. Callback signature header
`X-Chatchat-Signature-Version: 2` authenticates
`v2\nMETHOD\nPATH\nTIMESTAMP\nNONCE\nSHA256(raw body)` with HMAC; the body limit is
1 MiB. Java also checks the peer, freshness, replay nonce, contract hash, lease
and fencing token. The task contract remains `runtime_os.coding.v1`; signature
version and task version are independent. Unsupported v2 task contracts return
`VERSION_UNSUPPORTED`. The parent model's `model_native_analysis.v2` envelope is
a third, separate protocol and does not enable incremental Coding data supply.

The model-facing request contains only `operation`, `requestId`,
`datasetReference`, `instruction`, and `outputSchema`. The schema describes **one
JSONL object row**, not the file or an enclosing array. Each Python execution uses
a new sandbox; only the frozen input is mounted, and previous output files and
variables are absent. Runtime returns a scoped result reference and six evidence
references after successful archival. The main analysis can read the result using
its ordinary evidence operations.

The built-in `runtime-result-schema:v1` verifier checks structure. A successful
structural check is `INCONCLUSIVE` for business correctness, with
`businessValidation=NOT_EVALUATED`. A schema mismatch is `FAILED`. Domain
algorithms can be supplied only as explicitly registered `CodingResultVerifier`
extensions, with a stable verifier ID and version. The Decimal demonstration
verifier and expected acceptance answers live under `src/test`; they are not
production Runtime policies. Legacy plans without a verifier ID require their
original trusted extension; they must not silently become structural plans.

Deployment limits bound concurrency, deadline, input and output size, model and
code calls, and tokens. All Coding requests in one parent run share a persisted
Coding budget and use the parent's metered model. Reusing a request ID with
different content conflicts. A lost parent model binding cannot fall back to an
unmetered model. Completed task replay returns its recorded outcome.

An unknown provider or executor outcome retains its reservation and requires
reconciliation. Cancellation is not proof of provider stop. Do not force such
records to success or replay an unknown dispatch. A process restart can recover
archived, verified execution evidence; active SDK conversational state is not
durably restored by v1. The bounded parent wait occupies an analysis worker and
the configured semaphore caps concurrent Coding waits. Incremental data supply
and protocol v2 negotiation are not advertised by this adapter.

For release acceptance, run the focused Java tests, the Node tests, and
`ProductionCodingSmokeDriver` on an isolated instance with the deployed model and
Python environment. The driver independently checks two unrelated calculations,
structural-only status, result admission, parent metering, all six references,
and completed-task replay. Also submit a task through the authenticated production
task API: component acceptance alone does not verify existing Agent bindings,
published templates, data availability, or the acquisition workflow.
