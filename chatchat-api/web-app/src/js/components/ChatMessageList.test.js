// @vitest-environment jsdom

import MarkdownIt from "markdown-it";
import { createApp, h, nextTick, reactive } from 'vue';
import ChatMessageListVue from '../../components/ChatMessageList.vue';
import { mergeExecutionSteps } from '../views/ChatAssistantView';
import { describe, expect, it } from "vitest";
import ChatMessageList from "./ChatMessageList.js";
import { renderArtifactHtml, renderArtifactMarkdownHtml } from "../ui-artifact/registry.js";

const markdown = new MarkdownIt({ breaks: true });
const methods = ChatMessageList.methods;
it('shows running indicators and stops them when the task ends', async () => {
  const workbench = reactive({status:'active', terminal:false, steps:[], alerts:[], timings:[],
    allActivities:[{id:'one', sequence:1, state:'active', title:'正在计算', timeLabel:'10:00:00'}]});
  const component = {...ChatMessageListVue, methods:{...ChatMessageListVue.methods,
    executionWorkbench:()=>workbench}};
  const host = document.createElement('div');
  const app = createApp({render:()=>h(component,{messages:[{id:'running',role:'assistant',status:'running',taskId:'task'}]})});
  try {
    app.mount(host); await nextTick();
    expect(host.querySelectorAll('.workbench-spinner')).toHaveLength(3);
    expect(host.querySelector('[role="log"]').getAttribute('tabindex')).toBe('0');
    workbench.terminal=true;workbench.status='cancelled';await nextTick();
    expect(host.querySelectorAll('.workbench-spinner')).toHaveLength(0);
  } finally {app.unmount();}
});
it('follows new log events until the reader scrolls up, and resumes at the bottom', () => {
  const directive = ChatMessageList.directives.followActivity;
  const el = document.createElement('div');
  Object.defineProperties(el,{scrollHeight:{value:800,writable:true},clientHeight:{value:240}});
  directive.mounted(el);
  expect(el.scrollTop).toBe(800);
  el.scrollTop=100;el.dispatchEvent(new Event('scroll'));
  el.scrollHeight=900;directive.updated(el,{value:'new',oldValue:'old'});
  expect(el.scrollTop).toBe(100);
  el.scrollTop=660;el.dispatchEvent(new Event('scroll'));
  el.scrollHeight=1000;directive.updated(el,{value:'next',oldValue:'new'});
  expect(el.scrollTop).toBe(1000);
  el.scrollTop=600;directive.updated(el,{value:'next',oldValue:'next'});
  expect(el.scrollTop).toBe(600);
  directive.beforeUnmount(el);
});
it('renders five numbered business steps and keeps technical recovery receipts out of the DOM', async () => {
  const event = (type, sequence, state = 'RUNNING') => ({eventId:`event-${sequence}`, sequence,
    type:'RUNTIME_OBSERVATION',status:'RUNNING',payload:{payload:{metadata:{eventKind:'CODING_TRACE',
      codingEventType:type,eventState:state,errorMessage:state==='FAILED'?'输入文件不可读':''}}}});
  const message = reactive({id:'business-progress-test',role:'assistant',taskId:'task',status:'running',content:'',
    steps:mergeExecutionSteps([], [event('TASK_CREATED',1),event('TASK_STARTED',2)])});
  const host=document.createElement('div');document.body.append(host);
  const app=createApp({render:()=>h(ChatMessageListVue,{messages:[message],loading:true})});
  try {
    app.mount(host);await nextTick();
    const labels=()=>[...host.querySelectorAll('.workbench-phase-strip > li')].map(row=>row.textContent.match(/\d\/\d/)[0]);
    expect(labels()).toEqual(['1/5','2/5','3/5','4/5','5/5']);
    message.steps=mergeExecutionSteps(message.steps,Array.from({length:20},(_,i)=>event('RECOVERY_RECONCILED',i+3)));
    await nextTick();expect(labels()).toEqual(['1/5','2/5','3/5','4/5','5/5']);
    expect(message.steps).toHaveLength(22);
    expect(host.textContent).not.toMatch(/Cline|Python|RECOVERY_RECONCILED|BUDGET_UPDATED/);
    message.steps=mergeExecutionSteps(message.steps,[event('EXECUTION_FINISHED',23,'FAILED'),event('BUDGET_UPDATED',24)]);
    message.status='failed';await nextTick();
    expect(host.textContent).toContain('执行失败');expect(host.textContent).toContain('输入文件不可读');
    expect(host.querySelector('.thinking-message')).toBeNull();
    expect(host.querySelectorAll('.workbench-phase-strip > li')[3].className).toContain('error');
    expect(host.querySelector('[aria-label="实时执行动态"]')).not.toBeNull();
    expect(host.querySelector('[aria-label="阶段成果"]')).toBeNull();
    expect(host.querySelector('.runtime-progress-track')).toBeNull();
  } finally {app.unmount();host.remove();}
});
it('retains a collapsible execution summary beside completed reports',()=>{
  const context={isExecutionRunning:()=>false,messageVisualReport:()=>({status:'SUCCESS'}),messageUiArtifact:()=>null};
  const message={role:'assistant',taskId:'internal-task',status:'completed'};
  expect(methods.shouldShowSteps.call(context,message)).toBe(true);
  context.isExecutionRunning=()=>true;
  expect(methods.shouldShowSteps.call(context,message)).toBe(true);
  context.isExecutionRunning=()=>false;context.messageVisualReport=()=>null;
  expect(methods.shouldShowSteps.call(context,message)).toBe(true);
});
it('allows exporting a completed report even when the text fallback is empty',()=>{
  const context={messageHasRenderableContent:()=>false,messageVisualReport:()=>({schemaVersion:'python_visual_report.v1'}),messageUiArtifact:()=>null};
  expect(methods.canExportMessagePdf.call(context,{role:'assistant',status:'completed'})).toBeTruthy();
  expect(methods.canExportMessagePdf.call(context,{role:'assistant',streaming:true})).toBeFalsy();
  context.messageVisualReport=()=>null;context.messageUiArtifact=()=>({artifactId:'archived-report'});
  expect(methods.canExportMessagePdf.call(context,{role:'assistant',status:'completed'})).toBeTruthy();
});
it('renders archived workspace code verbatim without report cleanup and keeps HTML inert',()=>{
  const source="def total(values):\n    label = 'keep : literal [evidence:literal]'\n    return sum(values)\n";
  const content=`main.py\n\`\`\`\`python\n${source}\`\`\`\`\n<script>window.injected=true</script>`;
  const html=methods.renderMarkdown.call({},content,{memoryContext:{codingTaskProtocol:'runtime_os.coding.v2',codingResultFormat:'WORKSPACE_FILES'}});
  const body=new DOMParser().parseFromString(html,'text/html').body;
  expect(body.querySelector('pre code').textContent).toBe(source);
  expect(body.querySelector('script')).toBeNull();
  expect(body.textContent).toContain('<script>window.injected=true</script>');
});
const context = Object.fromEntries(
  Object.entries(methods).map(([name, method]) => [name, method])
);

function render(source) {
  const html = methods.collapseToolEvidenceHtml.call(context, markdown.render(source));
  return new DOMParser().parseFromString(`<main>${html}</main>`, "text/html").body.firstElementChild;
}

describe("Markdown document wrappers", () => {
  const body = "### 局限性与边界条件说明\n\n当前**未绑定手册**。\n\n- 查看 `database.schema.table_name`\n";

  it.each(["markdown", "md"])("renders existing %s-wrapped answers as documents", (language) => {
    const source = `\`\`\`${language}\n${body}\`\`\``;
    for (const html of [
      methods.renderMarkdown.call(context, source, { role: "assistant" }),
      renderArtifactMarkdownHtml(source)
    ]) {
      expect(html).toContain("<h3>局限性与边界条件说明</h3>");
      expect(html).toContain("<strong>未绑定手册</strong>");
      expect(html).toContain("<ul>");
      expect(html).toContain("<code>database.schema.table_name</code>");
      expect(html).not.toContain("<pre>");
    }
  });

  it("preserves nested code and escapes HTML within a document", () => {
    const source = '````markdown\n# Report\n\n```json\n{"value":1}\n```\n\n<script>alert(1)</script>\n````';
    const html = methods.renderMarkdown.call(context, source, { role: "assistant" });
    expect(html).toContain("<h1>Report</h1>");
    expect(html).toContain('class="language-json"');
    expect(html).not.toContain("<script>");
  });

  it("keeps Markdown examples and user-supplied source as code", () => {
    const source = `\`\`\`markdown\n${body}\`\`\``;
    expect(methods.renderMarkdown.call(context, source, { role: "user" })).toContain('class="language-markdown"');
    expect(methods.renderMarkdown.call(context, `Example:\n\n${source}`, { role: "assistant" }))
      .toContain('class="language-markdown"');
  });
});

describe("tool execution evidence", () => {
  it("collapses completed execution steps by default and allows reopening them", () => {
    const collapseContext = {
      ...context,
      expandedExecutionMessageIds: new Set(),
      loading: false
    };
    const completed = {
      id: "message-completed",
      role: "assistant",
      status: "completed",
      streaming: false,
      content: "report",
      steps: [{ id: "step-1", status: "done", title: "完成分析" }]
    };

    expect(methods.executionStepsExpanded.call(collapseContext, completed)).toBe(false);
    methods.toggleExecutionSteps.call(collapseContext, completed);
    expect(methods.executionStepsExpanded.call(collapseContext, completed)).toBe(true);
    expect(methods.executionStepsExpanded.call(collapseContext, {
      ...completed,
      id: "message-running",
      status: "streaming",
      streaming: true,
      content: ""
    })).toBe(true);
    expect(methods.executionStepsExpanded.call(collapseContext, {
      ...completed,
      id: "message-failed",
      status: "failed"
    })).toBe(true);
  });

  it("projects business steps while retaining the original technical event rows", () => {
    const message = {
      role: "assistant",
      status: "failed",
      streaming: false,
      steps: [
        { id: "runtime-start", type: "RUNTIME_STARTED", title: "后端执行中", status: "done" },
        {
          id: "planner-observation",
          type: "RUNTIME_OBSERVATION",
          title: "计划校验",
          toolName: "mcp_chatchat_mcp_server_api_template_query",
          status: "done"
        },
        { id: "runtime-failed", type: "RUNTIME_FAILED", title: "运行失败", status: "error" }
      ],
      traces: []
    };

    expect(methods.runtimeToolCalls.call(context, message)).toHaveLength(0);
    const stages = methods.runtimeProcessSteps.call(context, message);
    expect(stages.map((step) => step.id)).toEqual([1,2,3,4,5].map(number => `business-step-${number}`));
    expect(stages[0]).toMatchObject({status:'error'});
    expect(JSON.stringify(stages)).not.toContain('mcp_chatchat');
    expect(message.steps).toHaveLength(3);
  });

  it("groups child events from the same tool inside the realtime event stream", () => {
    const groupContext = { ...context, loading: false };
    const message = {
      id: "message-tool-group",
      role: "assistant",
      status: "completed",
      streaming: false,
      timestamp: 1_000,
      steps: [
        { id: "repair-1", type: "RUNTIME_OBSERVATION", title: "恢复 DAG", detail: "已恢复工作流", toolName: "dag_repair", status: "repaired", timestamp: 1_100 },
        { id: "repair-2", type: "RUNTIME_OBSERVATION", title: "校验 DAG", detail: "校验通过", toolName: "dag_repair", status: "repaired", timestamp: 1_200 }
      ]
    };

    const groups = methods.runtimeEventGroups.call(groupContext, message);
    expect(groups).toHaveLength(1);
    expect(groups[0]).toEqual(expect.objectContaining({
      id: "tool:dag_repair",
      grouped: true,
      toolName: "dag_repair",
      active: false,
      statusText: "已修复"
    }));
    expect(groups[0].children).toHaveLength(2);
  });

  it("treats the authoritative parent terminal state as final despite a stale child event", () => {
    const completed = {
      id: "message-terminal",
      role: "assistant",
      status: "completed",
      streaming: false,
      content: "report",
      steps: [{
        id: "stale-runtime-step",
        type: "RUNTIME_OBSERVATION",
        title: "后端处理中",
        detail: "正在执行后台任务",
        status: "active",
        timestamp: 1_000
      }]
    };

    const visible = methods.visibleExecutionSteps.call(context, completed);
    expect(visible[0].status).toBe("done");
    const stages = methods.runtimeProcessSteps.call(context, completed);
    expect(stages).toHaveLength(5);
    expect(stages[4]).toMatchObject({status:'done'});
    expect(stages[3].status).not.toBe('active');
    expect(methods.hasUnfinishedExecutionSteps.call(context, completed)).toBe(false);
    expect(methods.isExecutionRunning.call(context, completed)).toBe(false);
    expect(methods.runtimeStatusLabel.call(context, completed)).not.toBe("Run");
  });

  it("recognizes supporting datasets as evidence attachments", () => {
    expect(methods.isSupportingDatasetVisualization({
      presentationChannel: "supporting_dataset",
      ui: { role: "evidence_attachment", defaultCollapsed: true }
    })).toBe(true);
    expect(methods.isSupportingDatasetVisualization({
      type: "table",
      ui: { defaultView: "table" }
    })).toBe(false);
  });

  it("collapses raw tabular data by default without collapsing charts", () => {
    expect(methods.isCollapsibleRawDataVisualization.call(context, {
      type: "table",
      ui: { role: "raw_data", defaultCollapsed: true }
    })).toBe(true);
    expect(methods.rawDataVisualizationToggleLabel.call(context, {
      type: "table",
      dataset: { rowCount: 84, rows: [{ id: 1 }] },
      ui: { role: "raw_data", defaultCollapsed: true }
    })).toBe("查看原始数据（84 行）");
    expect(methods.isCollapsibleRawDataVisualization.call(context, {
      type: "chart",
      ui: { defaultCollapsed: true }
    })).toBe(false);
  });

  it("hides numbered web citation markers in dynamic report answers", () => {
    const markdownHtml = renderArtifactMarkdownHtml(
      "关注行业数据 [网页7][网页8]。\n\n风险提示 [网页3]。"
    );
    const richHtml = renderArtifactHtml(
      "<p>关注行业数据 [网页7]，并观察后续变化 [网页8]。</p>"
    );

    expect(markdownHtml).toContain("关注行业数据");
    expect(richHtml).toContain("关注行业数据");
    expect(markdownHtml).not.toMatch(/\[(?:网页|網頁)\s*\d+\]/);
    expect(richHtml).not.toMatch(/\[(?:网页|網頁)\s*\d+\]/);
  });

  it("hides decoded and full-width web markers when restoring history", () => {
    const html = methods.renderMarkdown.call(
      context,
      "历史结论 &#91;网页7&#93;，补充来源 ［网页\u200B８］。",
      { role: "assistant", sources: [], traces: [] }
    );
    const contractHtml = methods.renderMarkdown.call(
      context,
      "历史结论 &#91;网页7&#93;。",
      {
        role: "assistant",
        sources: [],
        traces: [],
        uiResponse: {
          contractVersion: "ui_response_v1",
          answer: "历史结论 &#91;网页7&#93;。",
          citations: []
        }
      }
    );

    expect(html).toContain("历史结论");
    expect(html).not.toContain("网页7");
    expect(html).not.toContain("网页\u200B８");
    expect(html).not.toContain("&#91;");
    expect(contractHtml).not.toContain("网页7");
    expect(contractHtml).not.toContain("&#91;");
  });

  it("repairs uneven Markdown tables inside structured answer blocks", () => {
    const report = [
      "## 当前持仓明细",
      "返回 2 条当前持仓。",
      "| 证券代码 (ZQDM) | 证券名称 (ZQMC) | 证券数量 (ZQSL) | 证券市值 (ZXSZ) | 当日盈亏 (DRYK) | 累计实现盈亏 (LJYK) |",
      "|---|---:|---:|---:|---:|",
      "| 600693 | 东百集团 | 2600 | 24544.00 | 749.61 | 5229.59 |",
      "| 000155 | 川能动力 | 800 | 9808.00 | 0.00 | 1033.50 |"
    ].join("\n");

    const html = methods.renderMarkdown.call(context, report, {
      role: "assistant",
      sources: [],
      traces: [],
      uiResponse: {
        contractVersion: "ui_response_v1",
        answerBlocks: [{ type: "markdown", text: report }],
        citations: []
      }
    });
    const root = new DOMParser().parseFromString(`<main>${html}</main>`, "text/html").body.firstElementChild;

    expect(root.querySelectorAll("table")).toHaveLength(1);
    expect(root.querySelectorAll("thead th")).toHaveLength(6);
    expect(root.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(root.querySelector(".query-result-table-toolbar")?.textContent).toContain("2 行 / 6 列");
    expect(root.textContent).not.toContain("|---|");
  });

  it("keeps tool execution evidence collapsed by default", () => {
    const element = render([
      "## 回答结论",
      "主要回答内容。",
      "## 工具执行证据",
      "- `document_search` [检索] success 证据类型: DOCUMENT duration ms: 20 summary: 找到文档"
    ].join("\n\n"));

    const details = element.querySelector("details.tool-evidence-details");
    expect(details).not.toBeNull();
    expect(details.hasAttribute("open")).toBe(false);
    expect(details.querySelector("summary")?.textContent).toContain("证据 · 工具执行证据");
    expect(details.querySelector("summary small")?.textContent).toBe("1 条");
    expect(details.querySelector(".tool-evidence-body")?.textContent).toContain("document_search");
  });

  it("keeps nested evidence headings inside and later peer sections outside", () => {
    const element = render([
      "## 工具调用证据",
      "### 调用摘要",
      "普通证据文本。",
      "## 后续建议",
      "这部分不应收起。"
    ].join("\n\n"));

    const details = element.querySelector("details.tool-evidence-details");
    expect(details.textContent).toContain("调用摘要");
    expect(details.textContent).not.toContain("后续建议");
    expect(element.querySelector(":scope > h2")?.textContent).toBe("后续建议");
  });
});
