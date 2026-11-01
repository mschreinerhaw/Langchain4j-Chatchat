/** Real authenticated Cline tasks. No Skill IDs are supplied in the user prompts. */
import fs from "node:fs";
import path from "node:path";
import assert from "node:assert/strict";
import { randomUUID, createHash } from "node:crypto";
const mode = process.argv[2],
  origin = process.env.SKILLS_ORIGIN,
  authPath = process.env.SKILLS_AUTH_FILE,
  out = process.env.SKILLS_OUTPUT;
assert.ok(
  ["analysis", "development"].includes(mode) && origin && authPath && out,
  "Usage: run-live.mjs analysis|development; set SKILLS_ORIGIN, SKILLS_AUTH_FILE, SKILLS_OUTPUT, and SKILLS_AGENT_ID for analysis"
);
const auth = JSON.parse(fs.readFileSync(authPath, "utf8").replace(/^\uFEFF/, ""));
fs.mkdirSync(out, { recursive: true });
assert.ok(
  !fs.existsSync(path.join(out, "submission.json")),
  "Preserve submitted tasks; use another output directory"
);
const save = (n, v) => fs.writeFileSync(path.join(out, n + ".json"), JSON.stringify(v, null, 2));
async function api(route, method = "GET", body) {
  const r = await fetch(origin + "/api/v1" + route, {
    method,
    headers: {
      Authorization: "Bearer " + auth.token,
      "Content-Type": "application/json",
      "X-Workspace-Id": "data-science",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json();
  assert.ok(r.ok && [200, 201, 202].includes(j.code), route + ": " + r.status + " " + j.message);
  return j.data;
}
const deadline = Date.now() + 20 * 60 * 1000;
async function pause() {
  assert.ok(Date.now() < deadline, "Task retained; observation timeout is not a cancellation");
  await new Promise((r) => setTimeout(r, 10000));
}
function loads(value, found = []) {
  if (typeof value === "string" && /^[\[{]/.test(value.trim())) {
    try {
      loads(JSON.parse(value), found);
    } catch {}
  } else if (value && typeof value === "object") {
    if (
      (value.type === "SKILLS_LOADED" || value.codingEventType === "SKILLS_LOADED") &&
      value.skillId
    )
      found.push(value);
    for (const v of Object.values(value)) loads(v, found);
  }
  return found;
}
if (mode === "development") {
  const request = {
    action: "generate",
    modelName: process.env.SKILLS_MODEL || "deepseek-v4-flash",
    skillIds: [],
    sourceCode: "# New program\n",
    selectedCode: "",
    prompt:
      "编写一个可维护的 Python 命令行小程序。summarize(values) 接受数字列表，返回 count 和 sum；支持空列表，拒绝非列表、布尔值、非数值和非有限数。请使用类型注解、清晰的参数校验、异常处理及 main 入口，从 CHATCHAT_INPUT_JSON 读取 values 并输出 JSON。返回完整 main.py 和使用标准库 unittest 的 test_main.py，测试应导入真实 main 模块，覆盖正常、空列表和非法输入，不复制实现。本任务仅编写源码，不执行代码或声称测试已执行。",
  };
  fs.writeFileSync(path.join(out, "request.md"), request.prompt + "\n");
  save("request", request);
  const plan = await api("/data-science/python/assist/plan", "POST", request);
  save("plan", plan);
  request.planId = plan.planId;
  const start = await api("/data-science/python/assist/coding", "POST", {
    request,
    requestId: "builtin-skills-dev-" + randomUUID(),
    entryPath: "main.py",
    files: [{ path: "main.py", content: request.sourceCode }],
  });
  save("submission", start);
  console.log(JSON.stringify({ mode, taskId: start.taskId }));
  let state;
  for (;;) {
    state = await api("/data-science/python/assist/coding/" + start.taskId);
    save("result", state);
    if (["COMPLETED", "FAILED", "PARTIAL", "CANCELLED", "BUDGET_EXCEEDED"].includes(state.status))
      break;
    console.log(JSON.stringify({ mode, status: state.status }));
    await pause();
  }
  const used = loads(state.events);
  save("selected-skills", { scope: "ACTUAL_CLINE_EVENT_PAYLOADS", events: used });
  assert.equal(state.status, "COMPLETED");
  assert.ok(
    used.some((s) => s.skillId === "builtin:python-code-development" && s.skillVersion === "1.0.0"),
    "Programming Skill not actually loaded"
  );
  assert.ok(state.output.files.some((f) => f.path === "main.py"));
  assert.ok(state.output.files.some((f) => f.path === "test_main.py"));
  for (const f of state.output.files) {
    assert.ok(/^[a-zA-Z0-9_-]+\.py$/.test(f.path));
    fs.writeFileSync(path.join(out, f.path), f.content);
  }
  save("verdict", {
    status: "PASS",
    scope: "REAL_CLINE_SOURCE_GENERATION_AND_SKILL_LOAD; NOT_EXECUTED_UNIT_TESTS",
    taskId: start.taskId,
    files: state.output.files.map((f) => ({
      path: f.path,
      sha256: createHash("sha256").update(f.content).digest("hex"),
    })),
  });
  console.log(JSON.stringify({ mode, status: "PASS", loaded: used.map((s) => s.skillId) }));
} else {
  const agent = process.env.SKILLS_AGENT_ID;
  assert.ok(agent);
  save("agent-config", await api("/agents/workshop/" + agent));
  const csv =
    "region,units,amount\n华东,2,120.50\n华南,3,89.50\n华东,1,40.00\n华南,0,0.00\n华北,2,75.25\n";
  fs.writeFileSync(path.join(out, "sales.csv"), csv);
  const form = new FormData();
  form.append("file", new Blob([csv]), "sales.csv");
  const r = await fetch(origin + "/api/v1/data-science/user-assets", {
    method: "POST",
    headers: { Authorization: "Bearer " + auth.token },
    body: form,
  });
  const j = await r.json();
  assert.ok(r.ok && j.code === 200);
  save("uploaded-input", j.data);
  const question =
    "请用 Python 分析附件 sales.csv，检查缺失值，计算总金额、总销量和各区域金额，并生成区域金额柱状图。附件是合成测试数据：region 为区域，units 为销量（件），amount 为金额（人民币元）；每行是一笔交易，零值是真实零。只使用本次附件，不查询数据库，不联网或安装依赖；结论应有实际计算依据。";
  fs.writeFileSync(path.join(out, "request.md"), question + "\n");
  const session = "builtin-skills-analysis-" + randomUUID(),
    body = {
      question,
      sessionId: session,
      idempotencyKey: session,
      attachmentIds: [j.data.id],
      parameters: { reportMode: "visual", taskType: "DATA_ANALYSIS" },
    };
  save("request", body);
  const start = await api("/published-agents/" + agent + "/questions", "POST", body);
  save("submission", start);
  console.log(JSON.stringify({ mode, taskId: start.taskId }));
  let state,
    cursor = 0,
    events = [];
  for (;;) {
    state = await api(
      "/published-agents/" +
        agent +
        "/questions/" +
        start.taskId +
        "/status?afterSequence=" +
        cursor +
        "&eventLimit=200"
    );
    cursor = state.eventCursor ?? cursor;
    events.push(...(state.events ?? []));
    save("state", state);
    save("events", events);
    if (state.terminal) break;
    console.log(JSON.stringify({ mode, status: state.status }));
    await pause();
  }
  const answer = await api("/published-agents/" + agent + "/questions/" + start.taskId + "/answer");
  save("answer", answer);
  const task = await api("/agent/tasks/" + start.taskId + "?tenantId=" + auth.user.tenantId);
  save("agent-task", task);
  fs.writeFileSync(path.join(out, "answer.md"), task.answerSummary || "");
  const trace = await api(
    "/agent/tasks/" + start.taskId + "/events?tenantId=" + auth.user.tenantId + "&limit=1000"
  );
  save("agent-trace", trace);
  const runs = [
    ...new Set(
      trace.flatMap((e) => {
        try {
          return [JSON.parse(e.payload).runId].filter(Boolean);
        } catch {
          return [];
        }
      })
    ),
  ];
  let observations = [];
  for (const run of runs) {
    const obs = await api("/agent/runtime/runs/" + run + "/observations?limit=1000");
    save("observations-" + run, obs);
    observations.push(...obs);
    save("coding-" + run, await api("/agent/runtime/runs/" + run + "/coding"));
  }
  const used = loads(observations);
  save("selected-skills", { scope: "ACTUAL_CLINE_EVENT_PAYLOADS", events: used });
  assert.ok(
    used.some((s) => s.skillId === "builtin:python-data-analysis" && s.skillVersion === "1.3.0"),
    "Analysis Skill not actually loaded"
  );
  assert.ok(["SUCCEEDED", "PARTIAL"].includes(task.canonicalState));
  save("verdict", {
    status: "REQUIRES_NUMERICAL_AND_EXECUTION_REVIEW",
    taskId: start.taskId,
    scope: "SKILL_LOAD_OBSERVED; DO_NOT_INFER_BUSINESS_PASS_FROM_TASK_STATE",
  });
  console.log(
    JSON.stringify({ mode, status: task.canonicalState, loaded: used.map((s) => s.skillId) })
  );
}
