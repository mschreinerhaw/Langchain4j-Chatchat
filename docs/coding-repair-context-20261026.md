# Coding repair context verification — 2026-10-26

## Baseline and observed failure

The clean local branch `feature/mcp-template-centric-permissions` was fast-forwarded
from `72dac06b1` to `e0271b164` (37 commits). The running worker's compiled
`runner.js` and `code-repair.js` exactly matched the synchronized sources compiled
as ES modules with TypeScript 5.9.3.

The latest failed business task was `7e9f9b3c-2148-4acc-94b5-673a04fb1cf3`,
using `server_ops_assistant`. Its four Python attempts failed as follows:

| Version | Source line | Failure |
| --- | --- | --- |
| 1 | 35 | Logical `datasetReference` lookup against unmapped storage columns |
| 2 | 325 | `texts.keys()` called on a local non-dictionary value |
| 3 | 154 | A parsed text row indexed without checking its length |
| 4 | 336 | The earlier `texts.keys()` failure was reintroduced |

The task stopped after its authorized repair limit. This was a generated-code
failure, distinct from the historical unreturned upstream model request.

## Change

The worker adds bounded `repairSourceContext` to stopped-execution feedback only
when Java allows repair, both feedback hashes match the actual executed program,
and the source frame identifies that script. Up to three locations include a
five-line excerpt, with each source line limited to 512 characters. Excerpts are
explicitly marked as untrusted source; no process output or exception values are
copied into this additional context.

Instructions bind exact edits to the latest program, retain earlier corrections,
and distinguish local variable type errors from unavailable package APIs.
Versioned input instructions now explicitly use the existing read-only
`runtime_input.load_rows` reader for logical columns, JSON cells and missing/null
semantics. Business calculations and repair admission remain model/Java-owned.
These changes improve repair context; they do not guarantee arbitrary generated
programs are correct.

## Validation and deployment

- Local worker: 54 tests passed, including real SDK execution and new source-context
  hash, frame, bounds and metadata exclusion tests.
- Production Node 24.21.0 and installed Cline SDK: 34 focused tests passed before
  service replacement.
- Synchronized Java supply/model regression: 44 tests passed across
  `PublishedTemplateSupplyTest`, `AgentWorkflowDecisionEngineTest`,
  `AnalysisCoverageCoordinatorTest` and `AgentChatModelResolverTest`.
- Worker image: `runtime-os-coding-agent:repair-context-20261026`.
- Image ID: `sha256:b5d531b183e8d42c99e05d99260c94b4fc2b92dc198291d91cfbffabf1291c65`.
- Deployed `runner.js`: `d0864bada0d7d7bb5ea3289abfc20a464bd2b60275c445b3342f27ffd11a02a5`.
- Deployed `code-repair.js`: `0bf8f74bf7b3cc15766ba5bb7997be50508b3b142107c63156ad933062c040b7`.
- Worker health passed. Application port 18100 and monitor port 18101 returned HTTP 200.
- User, environment, command, health check, resource limits, capabilities, read-only
  filesystem, mounts and network membership were compared with the original container.

The deployment replaces only two compiled Worker modules on the exact original
image. The API/MCP artifacts and business Agent configurations were not changed.
The retained rollback container is
`runtime-os-app-stage-coding-agent-1-before-repair-context-20261026`.

The current Compose symlink points to a missing platform-rendered file. Both
worker secret source paths were also missing, although their existing mounts
remained readable. Only those worker secret sources were recovered privately from
the running container. An initial replacement failed its health gate due to the
restored file group and automatically rolled back. The group was corrected to
the configured container group `32101`; the subsequent replacement passed.
Other missing platform deployment assets were not reconstructed. This release
uses the inspected runtime configuration; the external platform's release
inventory was not updated, so a later platform redeployment must include this fix.

Live callback verification subsequently exposed an omitted endpoint setting in
the deployment helper: it copied aliases but initially omitted the static
`IPAMConfig`. The replacement received `172.30.93.2`, while the unchanged API
callback allowlist admits only `172.30.93.10`; input-context callbacks returned
401 before model admission. The original fixed address was restored without
widening the allowlist or firewall. The helper now preserves and verifies the
address, detaches the stopped backup endpoint before replacement, and restores
that endpoint on rollback. A signed request from the restored worker reached
task validation (HTTP 400 for an intentionally empty task binding).

## Business acceptance

The official retry endpoint created task
`0ed3b169-6a77-456c-9f10-a0821a5c0626` from the original failed task. It retains
the original `server_ops_assistant` configuration and `qwen3.7-plus` model.
The original terminal failure record remains intact.

While that task was waiting in planning, an external configuration update changed
only the Agent's `modelName` to `qwen3.8-flash`. This deployment did not make that
change. The agent-created old-model verification was cancelled through the
official endpoint, and retried against the current Agent configuration. Its
cancellation is not recorded as successful analytical acceptance.

The next retry, `496f0c65-f6ec-4472-ad6e-c2192abe9080`, failed before data
acquisition: the first planning request timed out after 122,529 ms. The published
Flash connection had a 30,000 ms request timeout and three retries, explaining
the observed four-attempt duration. Through the official model save/publish API,
only its connection timeout was changed to 180,000 ms. The encrypted API key,
endpoint, model identity, retries, token limit, enabled/default state and other
connection fields were compared before/after and preserved. There were no
unpublished model changes before the update. Private rollback payloads are saved
with the deployment evidence. A further official retry tests the new timeout.

Task `78a20370-abea-4db1-becd-d837f8afe438` passed its first planning request
in 114,610 ms and subsequent retrieval-model requests in 122,111 ms and 52,075 ms.
Its persisted acquisition DAG completed successfully for asset discovery,
published-template query and Linux command execution. Initial Coding sessions
hit the callback IP mismatch described above before any model or Python start;
their failed receipts are retained.

After the fixed address was restored, the same Coding task recovered under fence
3 at 09:28:35 Asia/Shanghai. Its first Python program executed successfully and
produced an available, structurally accepted result (`validationStatus=INCONCLUSIVE`,
not independent business validation). The original field-access and local
dictionary-method exceptions did not recur in this execution.

The model reviewed that artifact and generated a second program. That process
exited successfully but did not write the required result file. The platform
correctly rejected it as `REQUIRED_OUTPUT_MISSING`; the earlier successful result
was not reused to claim success for the later failed proposal. A repair request
remained outstanding until the original 20-minute Coding deadline at 09:42:02.
The worker was cancelled and the parent ended `FAILED`.

**End-to-end report acceptance did not pass.** The deployed source/context repair,
network callback correction, acquisition path and first Python execution are
verified. A complete final business report remains unfinished; the later missing
output and delayed model repair must not be described as a successful report.
The extended connection timeout fixes the demonstrated 30-second planning
failure, but does not remove upstream latency or guarantee a completed repair
within the existing task budget. No further retries were launched after this
terminal result.

Sanitized [deployment evidence](coding-repair-context-20261026-evidence/deployment.json)
and [acceptance summary](coding-repair-context-20261026-evidence/acceptance-summary.json)
are checked into the working tree alongside this record.
Private payloads, backups, deployment checks and live acceptance records remain
on the server under `/opt/chatchat-releases/coding-repair-context-20261026/`.
