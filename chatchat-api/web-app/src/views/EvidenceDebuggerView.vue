<template>
  <section class="feature-view evidence-debugger">
    <header class="evidence-debugger-header">
      <div>
        <p>平台管理</p>
        <h1>证据决策调试</h1>
      </div>
    </header>

    <form class="evidence-debugger-query" @submit.prevent="runDecisionDebug">
      <label class="debugger-query-input">
        <Search :size="18" stroke-width="2" />
        <input v-model.trim="form.query" type="search" placeholder="输入检索问题或文档标题片段" />
      </label>
      <label class="debugger-number">
        <span>Top K</span>
        <input v-model.number="form.topK" min="1" max="20" type="number" />
      </label>
      <label class="debugger-toggle">
        <input v-model="form.debug" type="checkbox" />
        <span>Debug Trace</span>
      </label>
      <button
        class="debugger-refresh-inline"
        type="button"
        :class="{ loading: activeAction === 'refresh' }"
        :disabled="loading || !form.query"
        title="刷新调试结果"
        @click="runDecisionDebug('refresh')"
      >
        <RefreshCw :class="{ spinning: activeAction === 'refresh' }" :size="16" stroke-width="2" />
        <span>{{ activeAction === "refresh" ? "刷新中" : "刷新" }}</span>
      </button>
      <button class="primary" type="submit" :class="{ loading: activeAction === 'run' }" :disabled="loading || !form.query">
        <Search :class="{ pulse: activeAction === 'run' }" :size="17" stroke-width="2" />
        <span>{{ activeAction === "run" ? "运行中" : "运行" }}</span>
      </button>
    </form>

    <p v-if="loading" class="evidence-debugger-status">
      {{ activeAction === "refresh" ? "正在刷新证据决策轨迹..." : "正在运行证据决策调试..." }}
    </p>

    <p v-if="error" class="evidence-debugger-error">{{ error }}</p>

    <section class="decision-summary" aria-label="Decision summary">
      <article>
        <span>Action</span>
        <strong :class="decisionClass">{{ decision.action || "未执行" }}</strong>
      </article>
      <article>
        <span>Confidence</span>
        <strong>{{ percent(decision.confidence) }}</strong>
      </article>
      <article>
        <span>Match Type</span>
        <strong>{{ result?.matchType || "-" }}</strong>
      </article>
      <article>
        <span>Evidence Nodes</span>
        <strong>{{ evidenceNodes.length }}</strong>
      </article>
    </section>

    <section class="debugger-grid" aria-label="Evidence decision debugger">
      <article class="debugger-panel graph-panel">
        <header>
          <div>
            <p>Evidence Graph</p>
            <h2>证据关系图</h2>
          </div>
          <span :class="{ conflict: reasoning.conflictDetected }">
            {{ reasoning.conflictDetected ? "Conflict" : "Stable" }}
          </span>
        </header>

        <div class="graph-canvas">
          <div class="query-node">
            <span>Query</span>
            <strong>{{ graph.query || form.query || "-" }}</strong>
          </div>

          <div class="edge-list">
            <span v-for="edge in graphEdges" :key="edge.key">
              {{ edge.type }} · {{ percent(edge.confidence) }}
            </span>
            <span v-if="!graphEdges.length">No edges</span>
          </div>

          <div class="evidence-node-list">
            <article v-for="node in evidenceNodes" :key="node.nodeId" :class="['evidence-node', nodeClass(node)]">
              <header>
                <strong>{{ node.type }}</strong>
                <span>{{ node.evidenceGrade }} · {{ percent(node.confidence) }}</span>
              </header>
              <p>{{ node.text || node.section || node.fileName || node.nodeId }}</p>
              <footer>
                <span>{{ node.fileName || node.docId || "unknown source" }}</span>
                <small>{{ node.refId || node.chunkId || node.nodeId }}</small>
              </footer>
            </article>
            <p v-if="!evidenceNodes.length" class="debugger-empty">暂无证据节点</p>
          </div>
        </div>
      </article>

      <article class="debugger-panel trace-panel">
        <header>
          <div>
            <p>Decision Trace</p>
            <h2>规则执行轨迹</h2>
          </div>
          <span>{{ trace.selectedRuleId || "no rule" }}</span>
        </header>

        <div class="trace-list">
          <button
            v-for="step in traceSteps"
            :key="step.key"
            type="button"
            :class="{ matched: step.matched }"
            @click="selectedTraceKey = step.key"
          >
            <i>{{ step.priority }}</i>
            <span>
              <strong>{{ step.ruleId }}</strong>
              <small>{{ step.candidateAction }} · {{ step.matched ? "matched" : "skipped" }}</small>
            </span>
          </button>
          <p v-if="!traceSteps.length" class="debugger-empty">暂无决策轨迹</p>
        </div>

        <section v-if="selectedTrace" class="trace-detail">
          <h3>{{ selectedTrace.ruleId }}</h3>
          <p>{{ selectedTrace.reason || "No reason provided." }}</p>
          <dl>
            <template v-for="item in factEntries(selectedTrace)" :key="item.key">
              <dt>{{ item.key }}</dt>
              <dd>{{ item.value }}</dd>
            </template>
          </dl>
        </section>
      </article>

      <article class="debugger-panel reasoning-panel">
        <header>
          <div>
            <p>Reasoning</p>
            <h2>推理与决策原因</h2>
          </div>
          <span>{{ reasoning.conclusionMode || "INSUFFICIENT_EVIDENCE" }}</span>
        </header>

        <div class="reason-list">
          <article v-for="step in reasoningSteps" :key="step.key">
            <strong>{{ step.step }}</strong>
            <p>{{ step.description }}</p>
            <small>{{ percent(step.confidence) }} · {{ (step.evidenceNodeIds || []).join(", ") || "no evidence node" }}</small>
          </article>
          <p v-if="!reasoningSteps.length" class="debugger-empty">暂无推理链路</p>
        </div>

        <div class="decision-reasons">
          <strong>Decision Reasons</strong>
          <p v-for="reason in decision.reasons || []" :key="reason">{{ reason }}</p>
          <p v-if="!(decision.reasons || []).length" class="debugger-empty">暂无决策原因</p>
        </div>
      </article>
    </section>
  </section>
</template>

<script src="../js/views/EvidenceDebuggerView.js"></script>
