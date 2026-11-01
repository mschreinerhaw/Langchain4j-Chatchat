/** Validate real Sandbox/Skill receipts and the synthetic CSV independently, retaining uploaded bytes. */
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import crypto from "node:crypto";
const d = process.env.SKILLS_OUTPUT,
  origin = process.env.SKILLS_ORIGIN,
  auth = JSON.parse(fs.readFileSync(process.env.SKILLS_AUTH_FILE, "utf8").replace(/^\uFEFF/, ""));
assert.ok(d && origin);
const read = (n) => JSON.parse(fs.readFileSync(path.join(d, n + ".json"), "utf8")),
  save = (n, v) => fs.writeFileSync(path.join(d, n + ".json"), JSON.stringify(v, null, 2));
async function api(route) {
  const r = await fetch(origin + "/api/v1" + route, {
      headers: { Authorization: "Bearer " + auth.token },
    }),
    j = await r.json();
  assert.ok(r.ok && j.code === 200, route);
  return j.data;
}
const monitorName = fs.readdirSync(d).find((n) => /^coding-.*\.json$/.test(n)),
  monitor = read(monitorName.slice(0, -5)),
  t = monitor.tasks.at(-1),
  run = t.outcome.scope.runId;
const report = await api(
  "/agent/runtime/runs/" +
    run +
    "/coding/" +
    t.taskId +
    "/visual-report?resultRef=" +
    encodeURIComponent(t.outcome.resultDatasetRef)
);
save("visual-report", report);
assert.equal(report.artifactValidationStatus, "PASSED");
assert.equal(report.execution.executorStopped, true);
const execution = t.executions.find((e) => e.executionId === report.execution.operationId);
assert.ok(execution);
assert.equal(execution.status, "SUCCEEDED");
assert.equal(execution.exitCode, 0);
assert.equal(execution.stage, "VALIDATED");
assert.equal(execution.codeHash, report.execution.codeSha256);
const artifacts = [];
for (const [kind, a] of [
  ["code", report.execution.codeArtifact],
  ...report.charts.map((c) => [c.kind, c.artifact]),
]) {
  const response = await fetch(
    origin + "/api/v1/interactions/user-assets/" + a.fileId + "/content",
    { headers: { Authorization: "Bearer " + auth.token } }
  );
  assert.equal(response.status, 200);
  const b = Buffer.from(await response.arrayBuffer()),
    sha = crypto.createHash("sha256").update(b).digest("hex");
  assert.equal(sha, a.sha256 ?? report.execution.codeSha256);
  const ext = { code: "py", png: "png", plotly: "json" }[kind] ?? "artifact";
  fs.writeFileSync(path.join(d, a.fileId + "." + ext), b);
  artifacts.push({ kind, fileId: a.fileId, sha256: sha, bytes: b.length });
}
const csv = fs.readFileSync(path.join(d, "sales.csv"), "utf8").trim().split(/\r?\n/);
assert.equal(csv.shift(), "region,units,amount");
const cents = (s) => {
    const [a, b = ""] = s.split(".");
    return BigInt(a + (b + "00").slice(0, 2));
  },
  groups = new Map();
let total = 0n,
  units = 0n;
for (const line of csv) {
  const [region, u, a] = line.split(",");
  assert.ok(region && u && a);
  let g = groups.get(region) ?? { amount: 0n, units: 0n, count: 0n };
  g.amount += cents(a);
  g.units += BigInt(u);
  g.count++;
  groups.set(region, g);
  total += cents(a);
  units += BigInt(u);
}
const table = report.tables.find((t) => t.id === "t-region");
assert.equal(table.rows.length, groups.size + 1);
let cells = 0;
for (const row of table.rows.slice(0, -1)) {
  const g = groups.get(row[0]);
  assert.ok(g);
  assert.equal(cents(row[1]), g.amount);
  assert.equal(BigInt(row[2]), g.units);
  assert.equal(BigInt(row[3]), g.count);
  cells += 3;
}
assert.equal(cents(table.rows.at(-1)[1]), total);
assert.equal(BigInt(table.rows.at(-1)[2]), units);
assert.equal(BigInt(table.rows.at(-1)[3]), BigInt(csv.length));
assert.equal(cents(report.metrics.find((m) => m.id === "m-total-amount").value), total);
assert.equal(BigInt(report.metrics.find((m) => m.id === "m-total-units").value), units);
for (const row of report.tables.find((t) => t.id === "t-quality").rows) assert.equal(row[1], "0");
save("execution-and-numerical-validation", {
  status: "PASS",
  codingTaskId: t.taskId,
  actualExecutions: t.executions.map((e) => ({
    id: e.executionId,
    status: e.status,
    exitCode: e.exitCode,
    codeHash: e.codeHash,
  })),
  finalExecution: execution,
  artifacts,
  independentBaseline: {
    scope: "ORIGINAL_SYNTHETIC_CSV; EXACT_INTEGER_CENTS",
    rows: csv.length,
    totalCents: String(total),
    units: String(units),
    regions: groups.size,
  },
  comparedRegionalSourceCells: cells,
  analysisStatus: report.analysisStatus,
  scope:
    "REAL_CLINE_AND_PYTHON_EXECUTION, UPLOAD_INTEGRITY, CSV_RECONCILIATION; NOT_A_UI_CHART_RENDER_TEST",
});
save("verdict", {
  status: "PASS",
  scope: "AUTO_SKILL_DISCOVERY_AND_REAL_ANALYSIS",
  agentTaskId: read("agent-task").taskId,
  codingTaskId: t.taskId,
  analysisStatus: report.analysisStatus,
  limitations: report.limitations,
});
console.log(
  JSON.stringify({
    status: "PASS",
    codingTaskId: t.taskId,
    totalCents: String(total),
    units: String(units),
    artifacts: artifacts.length,
  })
);
