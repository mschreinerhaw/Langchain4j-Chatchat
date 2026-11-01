/** Reconcile this acceptance scenario against independent JDBC, never inject expectations into Agent. */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import assert from "node:assert/strict";
const root = process.argv[2] || "docs/studio-template-query-collection";
const read = (p) => JSON.parse(fs.readFileSync(path.join(root, p), "utf8"));
const sha = (p) => crypto.createHash("sha256").update(fs.readFileSync(p)).digest("hex");
const baseline = read("independent-jdbc.json").rows;
const studio = read("studio/state.json");
const sourceChecks = [];
for (const key of ["summary", "detail"]) {
  const { file, row } = studio[key];
  assert.equal(file.content, row.sqlTemplate);
  assert.equal(
    crypto.createHash("sha256").update(file.content).digest("hex"),
    row.source.sqlDigest
  );
  assert.equal(row.testEvidence.sqlDigest, row.source.sqlDigest);
  assert.equal(row.testEvidence.success, true);
  assert.equal(row.state, "PUBLISHED");
  assert.equal(row.publication.state, "RUNNING");
  assert.equal(row.templateKey, "sql_datasource:" + row.code);
  sourceChecks.push({
    template: row.code,
    savedTestedPublishedDigest: row.source.sqlDigest,
    tool: row.publication.toolName,
  });
}
const extract = (dir) => {
  const receipts = [];
  const walk = (v) => {
    if (typeof v === "string" && /^[{[]/.test(v.trim())) {
      try {
        walk(JSON.parse(v));
      } catch {}
    } else if (v && typeof v === "object") {
      if (v.dataSchema === "sql_result.v1" && v.success === true && Array.isArray(v.data?.rows))
        receipts.push({
          template: v.sourceMetadata?.operation?.templateId,
          target: v.target?.id,
          generatedAt: v.generatedAt,
          mode: v.operation?.diagnostics?.executionMode,
          parameters: v.operation?.diagnostics?.templateParameters,
          rows: v.data.rows,
        });
      for (const x of Object.values(v)) walk(x);
    }
  };
  for (const f of fs
    .readdirSync(path.join(root, dir))
    .filter((f) => /^observations-.*\.json$/.test(f)))
    walk(read(dir + "/" + f));
  // Nested raw-result wrappers repeat the same receipt; these are not extra SQL executions.
  return [...new Map(receipts.map((r) => [JSON.stringify(r), r])).values()];
};
const normalize = (v) => (v === null ? null : String(v).replaceAll(",", ""));
const fields = ["w_warehouse_sk", "w_warehouse_name", "w_country", "w_state", "w_warehouse_sq_ft"];
const canonical = (rows) =>
  rows.map((r) => fields.map((k) => normalize(r[k]))).sort((a, b) => Number(a[0]) - Number(b[0]));
const analysis = extract("agent-analysis");
const parameter = extract("agent-parameter-change");
const detailCode = studio.detail.row.code,
  summaryCode = studio.summary.row.code;
const normal = analysis.find((r) => r.template === detailCode && r.parameters.min_area === 0);
const changed = parameter.find(
  (r) => r.template === detailCode && r.parameters.min_area === 294242
);
const summary = analysis.find((r) => r.template === summaryCode);
assert.ok(
  normal && changed && summary,
  "Actual prepared SQL receipts required for both parameters"
);
for (const r of [normal, changed, summary]) {
  assert.equal(r.mode, "PREPARED_STATEMENT");
  assert.equal(r.target, studio.detail.row.datasourceId);
}
const nonnull = baseline.filter((r) => r.w_warehouse_sq_ft !== null);
const selected = baseline.filter(
  (r) => r.w_warehouse_sq_ft !== null && Number(r.w_warehouse_sq_ft) >= 294242
);
assert.deepEqual(canonical(normal.rows), canonical(nonnull));
assert.deepEqual(canonical(changed.rows), canonical(selected));
const areas = nonnull.map((r) => Number(r.w_warehouse_sq_ft));
assert.deepEqual(summary.rows, [
  {
    w_country: "United States",
    w_state: "TN",
    warehouse_count: baseline.length,
    total_warehouse_sq_ft: areas.reduce((a, b) => a + b, 0),
    min_warehouse_sq_ft: Math.min(...areas),
    max_warehouse_sq_ft: Math.max(...areas),
  },
]);
const report = read("agent-analysis/visual-report.json");
const detailTable = report.tables.find((t) => t.id === "tbl-detail");
assert.deepEqual(
  detailTable.rows.map((r) => r.map(normalize)),
  canonical(nonnull)
);
const aggTable = report.tables.find((t) => t.id === "tbl-region");
assert.deepEqual(
  aggTable.rows.map((r) => r.map(normalize)),
  [["United States", "TN", "5", "2031767", "138504", "977787"]]
);
const metrics = Object.fromEntries(report.metrics.map((m) => [m.id, normalize(m.value)]));
assert.equal(metrics["m-count-gap"], "1");
assert.equal(metrics["m-total"], "2031767");
const proof = read("agent-analysis/python-and-artifact-validation.json");
assert.equal(proof.execution.status, "SUCCEEDED");
assert.equal(proof.execution.executorStopped, true);
for (const a of proof.artifacts) {
  const f = fs
    .readdirSync(path.join(root, "agent-analysis"))
    .find((f) => f.startsWith(a.fileId + "."));
  assert.ok(f);
  assert.equal(sha(path.join(root, "agent-analysis", f)), a.sha256);
}
const codingFile = fs
  .readdirSync(path.join(root, "agent-analysis"))
  .find((f) => /^coding-.*\.json$/.test(f));
const coding = read("agent-analysis/" + codingFile);
const successful = coding.tasks
  .flatMap((t) => t.executions || [])
  .filter((e) => e.status === "SUCCEEDED" && e.exitCode === 0);
assert.ok(successful.length);
const charts = read("agent-analysis/chart-rendering.json");
assert.equal(charts.status, "RENDERED_IN_SYSTEM_PAGE");
assert.equal(charts.charts.length, 3);
assert.deepEqual(charts.diagnostics, []);
const paramResult = read("agent-parameter-change/agent-result.json");
const answer = paramResult.answerSummary.replaceAll(",", "");
assert.ok(answer.includes("1893263"), "Changed query sum must be in actual Agent answer");
const result = {
  status: "REAL_QUERY_ANALYSIS_ARTIFACT_AND_PARAMETER_CHANGE_VERIFIED",
  scope:
    "248 TPCDS warehouse; superadministrator role; independent JDBC comparison. Data discrepancies and semantic limits are retained, not rewritten as a full business-validation pass.",
  capturedAt: new Date().toISOString(),
  sourceChecks,
  actualQueryReceipts: [summary, normal, changed],
  reportTableCellsCompared: 26,
  python: {
    successfulExecutions: successful.length,
    validationStatuses: successful.map((e) => e.validationStatus),
    repairedExecutions: coding.tasks
      .flatMap((t) => t.executions || [])
      .filter((e) => e.status === "FAILED").length,
    codeSha256: proof.execution.codeSha256,
  },
  renderedCharts: charts.charts.map((c) => ({ id: c.id, kind: c.kind })),
  analysisTaskState: read("agent-analysis/agent-result.json").canonicalState,
  analysisStatus: report.analysisStatus,
  businessValidationStatus: proof.businessValidation.status,
  parameterTaskState: paramResult.canonicalState,
  changedParameter: {
    min_area: 294242,
    warehouseIds: selected.map((r) => Number(r.w_warehouse_sk)),
    count: selected.length,
    total: selected.reduce((s, r) => s + Number(r.w_warehouse_sq_ft), 0),
  },
  limitations: report.limitations,
};
fs.writeFileSync(
  path.join(root, "chain-reconciliation.json"),
  JSON.stringify(result, null, 2) + "\n"
);
console.log(
  JSON.stringify({
    status: result.status,
    analysisTaskState: result.analysisTaskState,
    businessValidationStatus: result.businessValidationStatus,
    pythonValidationStatuses: result.python.validationStatuses,
    changedParameter: result.changedParameter,
  })
);
