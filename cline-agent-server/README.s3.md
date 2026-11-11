# Native Cline / Python / S3 service

This opt-in transport accepts authorized snapshots from any business system. It
uses the existing HTTP/SSE Native Server and restricted Executor, with one
official Standalone Core per isolated task container. No SDK Worker, Python
Sandbox API, code-generation loop, or business-specific script is started by
this transport. The business system remains the task authority and verifier.

The implementation is under validation. Do not switch the production Coding SPI
based on the image build or these protocol tests. The existing `NativeSiloTaskService`
and the new `NativeS3NodeClient` do **not** yet constitute complete production
Runtime HTTP/SPI wiring. The legacy adapter refuses an unnegotiated Native Silo
profile instead of falling back to the Python Sandbox.

## Authority and S3 prerequisites

Use the existing Silo bucket, with object versioning enabled. The caller keeps
S3 credentials and the issuer key. The gateway receives only expiring task
capability URLs. Core receives neither S3 capabilities nor the issuer/provider
key. Its model access uses a task-only broker token. Input files are downloaded
before Core starts and bind-mounted read-only. Outputs stay local until Core and
its descendants stop; uploaded objects remain pending independent validation.

Configure the Executor's tenant/user/model/tool/template allowlists separately
from signed task requests. An empty template is appropriate for arbitrary S3
inputs. Do not reuse writable templates or another task's state. The Executor
must enforce CPU/memory/PID limits; the merged image alone is not isolation.

The authority signs the existing v2 task grant with HMAC-SHA256:

```json
{
  "version": 2,
  "tenantId": "tenant-a", "userId": "user-a",
  "taskId": "task-a", "sessionId": "session-a",
  "workspaceRef": "project", "modelProfile": "fixed",
  "budgetPolicy": {"status": "DEFERRED_STAGE_2_REVIEW"},
  "toolPermissions": ["read_files", "search_codebase", "editor", "apply_patch", "run_commands", "skills"],
  "dataPermissions": [], "networkPolicy": "broker-only",
  "expiresAt": 1794400000000,
  "runtimeProfile": "native-python-silo-v1",
  "deliveryBinding": {
    "tenantId": "tenant-a", "taskId": "task-a", "runId": "run-a",
    "inputSnapshotVersion": "authorized-snapshot-v1"
  },
  "s3": {
    "origin": "http://127.0.0.1:9000",
    "inputPrefix": "/runtime-assets/tenant-a/task-a/run-a/input/",
    "outputPrefix": "/runtime-assets/tenant-a/task-a/run-a/output/",
    "inputManifestSha256": "<SHA-256 of the exact canonical input inventory>"
  }
}
```

Use an actual current expiry (at most 24 hours), not the example timestamp.
`budgetPolicy.status` is a retained transport schema field; it grants no business
approval and imposes no model, tool, or repair iteration limit. The deadline and
Executor resource limits still apply. Actual consumption remains in native events.

`signContract` in `src/task-contract.mjs` and Java `NativeS3NodeClient.signGrant`
produce `base64url(JSON).base64url(HMAC(body))`. The issuer must first apply its
existing business authorization. Never give this grant to the Agent itself.

The canonical input inventory is an array sorted by `path` using ordinal UTF-16
comparison. Each object has fields in this order:
`path,size,sha256,objectVersion,objectPath`. `objectPath` is the URL pathname;
presigned query signatures are excluded. Serialize compact JSON, UTF-8, then
SHA-256. Java and Node have a shared expected-hash test vector. Different keys,
versions, bytes, local paths, tenants, tasks or immutable grants are rejected.

## HTTP sequence

All requests carry `Authorization: Bearer <signed-task-grant>`. A stable
`Idempotency-Key` is required for creation and each message. Capabilities are
accepted only from the trusted caller, never from model messages.

1. `POST /v1/sessions`, body `{"inputs":[...]}`. Each input has `path` under
   `input/`, `size`, `sha256`, `objectVersion`, and a GET `url` with matching
   `versionId`. Metadata files such as schema/mapping may be supplied as inputs.
   Empty inputs are permitted only when authorized by the signed empty inventory.
2. `GET /v1/sessions/<sessionId>/status` until `workerState=READY`.
3. `POST /v1/sessions/<sessionId>/messages`, body `{"text":"business objective"}`.
   The Agent chooses its source names, modules, commands and reports. If configured,
   the gateway resolves native permission requests using the frozen tool grant;
   it does not select an editing or repair strategy.
4. `GET /v1/sessions/<sessionId>/events` for original SSE events. Use
   `Last-Event-ID` for replay, including after sealing. These events retain failed
   tool calls, traceback/stderr, native results and usage. They are not synthetic
   progress records. Native iteration events are not a substitute for independent
   provider request accounting; raw audit logs preserve requests/responses.
5. After a completed turn, `POST .../delivery-preview` with
   `{"deliveryVersion":"v1"}`. Core must have written a selection:

   ```json
   {"schemaVersion":"coding_node_delivery_selection.v1","artifacts":[
     {"path":"portfolio/positions.py","type":"SOURCE"},
     {"path":"results/accounts.json","type":"RESULT"},
     {"path":"reports/accounts.md","type":"REPORT"}
   ]}
   ```

   There is no fixed `analysis.py` or fixed directory layout for source/output.
   The Host recomputes hashes and native receipts; it does not trust model-supplied
   bindings, checksums or completion claims.
6. `POST .../delivery-content` with `deliveryVersion` and `path` reads a declared
   candidate of at most 8 MiB as base64 plus exact size/SHA. Verify it against the
   candidate inventory. The caller may send **actual validation errors** as the
   next message in the same Session, using a fresh idempotency key and subsequent
   delivery version. A preview is not a sealed or formally accepted result.
7. `POST .../delivery`, body `{"deliveryVersion":"v1"}`, seals Core, confirms
   container stop, rehashes final artifacts, and preserves the native journal.
   Once sealed, no new model turns are admitted. Review large final files through
   the existing S3 delivery/validation flow. The final manifest includes tenant,
   task, run, snapshot version, delivery version, native receipts and typed files.
8. The caller issues task-scoped PUT URLs and sends `POST .../upload`:

   ```json
   {
     "manifestSha256": "<SHA-256 of compact final manifest JSON>",
     "artifacts": [{"path":"portfolio/positions.py","parts":[{"url":"<PUT capability>"}]}],
     "manifestUrl": "<PUT capability for delivery_manifest.json>",
     "evidenceUrl": "<PUT capability for original native events JSON>"
   }
   ```

   Supply every artifact, with `max(1,ceil(size/8388608))` part objects. This reuses
   Runtime's chunk-object protocol, **not** S3 `UploadPart` upload IDs. Every PUT
   must return a version ID. The caller verifies/assembles exact immutable part
   versions through its existing storage service. Single-part small files are
   ordinary S3 PUTs. All destinations are authorized before the first byte is sent.
   Three bounded transport attempts are independent of Agent repair iterations.
9. Runtime independently re-reads versioned objects, checks every size/SHA and the
   native journal, validates business numbers/report claims, and registers the
   result. Only then send `POST .../acceptance` with `manifestSha256`, `status`
   (`PASSED`/`FAILED`) and an immutable `validationRef`. This records **the caller's**
   verdict; the gateway does not claim to have validated its business meaning.

`POST .../cancel` revokes the task and aborts transfers, stops its actual Worker,
and rejects late upload/acceptance. Already written staging object versions may
remain; they cannot become formal results. Final acceptance is immutable. Reusing
a canceled task/session grant cannot create another Worker. Start a separately
authorized run rather than changing an old immutable task. True persisted Native
Session restoration across process loss is still pending; current recovery is
fail-closed and never replays unknown side effects or silently starts a new Agent.

## Build, deployment and rollback

Use `docker/Dockerfile.python` with `scripts/build-python-node.py`. The existing
Python lock is reused and checked package by package, followed by `pip check`,
real imports, native Node/SQLite checks and chart/font rendering. Runtime files
and the pinned official source commit are integrity checked. Base arguments must
be immutable image IDs/digests. A previously verified unified image can be reused
as the Python/Node base when the original base images are unavailable; this is
recorded in build evidence, not presented as a fresh original-base build.

`scripts/install-s3-gateway.py` builds and starts an **opt-in** gateway, Executor
and private model audit proxy. It requires an existing independently pinned
Executor daemon policy and private model settings. `--docker-host` explicitly
selects the existing build daemon; the installer does not create/reconfigure a
Docker daemon or alter the production model registry. Use a fresh deployment root
and unoccupied loopback ports. Keep Unix socket paths below Linux's length limit.
Generated secrets remain in `<deployment>/private/`, outside build context.

The installer records image ID, actual RepoDigests, process IDs/start ticks,
health and build/source evidence. An image ID is not a registry manifest digest.
`scripts/export-oci-evidence.py` can produce a standalone OCI layout and its
manifest digest without publishing to a registry. Label these two forms accurately.

Rollback is traffic/configuration rollback to the previous opt-in endpoint and
pinned image. Before stopping an owned process, compare its `/proc/<pid>/stat`
start ticks with `deployment.json`, and verify its private config binding. Stop
the gateway first so it revokes/stops active tasks, then its Executor and audit
proxy. Keep images, run evidence, Silo versions and task registries. No global
`prune`, shared daemon restart, directory deletion or production default switch
is part of this workflow. Boot supervision and an application rollback rehearsal
must be completed before production acceptance.

## Verification boundaries

`test/s3-gateway-http.test.mjs` uses protocol fixtures to test real HTTP/Unix/SSE
transport, validation state transitions, idempotency, scope denial and upload
cancellation. It is explicitly not real Silo/model/business acceptance.
`test/generic-s3-live.py` runs an actual model/Core against an explicit synthetic
CSV fixture, with independent arithmetic, multi-file, PNG and real TypeError
checks; it supplies no analysis program. It does not substitute for authorized
customer data, Java application HTTP/SPI integration, real S3 delivery, native CLI
comparison, or A–H five-run acceptance. Preserve every failed sample.
