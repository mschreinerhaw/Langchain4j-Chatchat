<template>
  <section :class="['feature-view agent-runtime-view', { 'agent-runtime-embedded': embedded }]">
    <header v-if="!embedded" class="agent-runtime-head">
      <div>
        <p>Agent Runtime</p>
        <h1>Run Control Plane</h1>
      </div>
      <div class="agent-runtime-actions">
        <button type="button" :disabled="loading" title="Refresh" @click="loadRuntime">
          <RefreshCw :size="16" stroke-width="2" />
          <span>{{ loading ? "Refreshing" : "Refresh" }}</span>
        </button>
        <label class="agent-runtime-toggle">
          <input v-model="autoRefresh" type="checkbox" />
          <span>Auto</span>
        </label>
      </div>
    </header>

    <p v-if="error" class="agent-runtime-error">{{ error }}</p>

    <section v-if="!embedded" class="agent-runtime-metrics" aria-label="Runtime snapshot">
      <article v-for="metric in metrics" :key="metric.label">
        <component :is="metric.icon" :size="18" stroke-width="2" />
        <span>{{ metric.label }}</span>
        <strong>{{ metric.value }}</strong>
      </article>
    </section>

    <section class="agent-runtime-filters" aria-label="Run filters">
      <label>
        <span>Status</span>
        <select v-model="filters.status" @change="applyFilters">
          <option value="">All</option>
          <option v-for="status in statusOptions" :key="status" :value="status">{{ status }}</option>
        </select>
      </label>
      <label>
        <span>Tenant</span>
        <input v-model.trim="filters.tenantId" type="text" placeholder="tenant-id" @keyup.enter="applyFilters" />
      </label>
      <label>
        <span>User</span>
        <input v-model.trim="filters.userId" type="text" placeholder="user-id" @keyup.enter="applyFilters" />
      </label>
      <label>
        <span>Conversation</span>
        <input
          v-model.trim="filters.conversationId"
          type="text"
          placeholder="conversation-id"
          @keyup.enter="applyFilters"
        />
      </label>
      <label class="agent-runtime-search">
        <span>Search</span>
        <div>
          <Search :size="15" stroke-width="2" />
          <input v-model.trim="filters.keyword" type="text" placeholder="run, query, tool" />
        </div>
      </label>
      <button type="button" :disabled="loading" title="Apply filters" @click="applyFilters">
        <ListFilter :size="16" stroke-width="2" />
        <span>Apply</span>
      </button>
    </section>

    <section v-if="!embedded" class="agent-runtime-rules" aria-label="Retrieval rules">
      <header class="agent-runtime-rules-head">
        <div>
          <p>Retrieval Rules</p>
          <strong>Active v{{ currentRuleActiveVersion }} · {{ formatTime(retrievalRules.refreshedAt) }}</strong>
        </div>
        <button type="button" :disabled="rulesLoading" title="Publish current rule type" @click="publishCurrentRules">
          <Save :size="16" stroke-width="2" />
          <span>Publish Tab</span>
        </button>
        <button type="button" :disabled="rulesLoading" title="Publish all rule types" @click="publishAllRules">
          <Save :size="16" stroke-width="2" />
          <span>Publish All</span>
        </button>
        <button type="button" :disabled="rulesLoading" title="Refresh rule cache" @click="refreshRules">
          <RefreshCw :size="16" stroke-width="2" />
          <span>{{ rulesLoading ? "Refreshing" : "Refresh Cache" }}</span>
        </button>
      </header>

      <nav class="agent-runtime-rule-tabs" aria-label="Rule tabs">
        <button
          v-for="tab in ruleTabs"
          :key="tab.key"
          :class="{ active: ruleTab === tab.key }"
          type="button"
          @click="ruleTab = tab.key"
        >
          <SlidersHorizontal :size="15" stroke-width="2" />
          <span>{{ tab.label }}</span>
          <strong>{{ tab.count }}</strong>
        </button>
      </nav>

      <p v-if="rulesError" class="agent-runtime-error">{{ rulesError }}</p>

      <div v-if="currentRuleVersions.length" class="agent-runtime-rule-versions">
        <button
          v-for="version in currentRuleVersions"
          :key="`${version.type}-${version.version}`"
          :class="{ active: version.active }"
          type="button"
          :disabled="rulesLoading || version.active"
          title="Activate rule version"
          @click="activateRuleVersion(version)"
        >
          <span>v{{ version.version }}</span>
          <strong>{{ version.active ? "ACTIVE" : "ACTIVATE" }}</strong>
        </button>
      </div>

      <div v-if="ruleTab === 'intent'" class="agent-runtime-rule-grid">
        <form class="agent-runtime-rule-form" @submit.prevent="saveRule('intent')">
          <label>
            <span>Intent</span>
            <input v-model.trim="ruleForms.intent.intent" type="text" placeholder="TROUBLESHOOTING" />
          </label>
          <label>
            <span>Name</span>
            <input v-model.trim="ruleForms.intent.name" type="text" placeholder="Login failures" />
          </label>
          <label>
            <span>Keywords</span>
            <textarea v-model.trim="ruleForms.intent.keywords" rows="3" placeholder="error, failure, timeout"></textarea>
          </label>
          <label class="agent-runtime-rule-wide">
            <span>Regex</span>
            <input v-model.trim="ruleForms.intent.regex" type="text" placeholder=".*(error|exception|fail).*" />
          </label>
          <label>
            <span>Weight</span>
            <input v-model.number="ruleForms.intent.weight" type="number" min="1" />
          </label>
          <label>
            <span>Priority</span>
            <input v-model.number="ruleForms.intent.priority" type="number" />
          </label>
          <label class="agent-runtime-rule-check">
            <input v-model="ruleForms.intent.enabled" type="checkbox" />
            <span>Enabled</span>
          </label>
          <div class="agent-runtime-rule-actions">
            <button type="submit" :disabled="rulesLoading" title="Save intent rule">
              <Save :size="16" stroke-width="2" />
              <span>{{ ruleForms.intent.id ? "Update" : "Add" }}</span>
            </button>
            <button type="button" title="New intent rule" @click="resetRuleForm('intent')">
              <Plus :size="16" stroke-width="2" />
              <span>New</span>
            </button>
          </div>
        </form>

        <div class="agent-runtime-rule-list">
          <article v-for="rule in retrievalRules.intentRules" :key="`intent-${rule.id}`">
            <button type="button" title="Edit rule" @click="editRule('intent', rule)">
              <strong>v{{ rule.version || 1 }} · {{ rule.name || rule.intent }}</strong>
              <span>{{ rule.keywords || rule.regex || "-" }}</span>
            </button>
            <small>W{{ rule.weight || 1 }} P{{ rule.priority || 0 }} {{ rule.enabled ? "ON" : "OFF" }}</small>
            <button type="button" title="Delete rule" @click="deleteRule('intent', rule)">
              <Trash2 :size="15" stroke-width="2" />
            </button>
          </article>
          <p v-if="!rulesLoading && retrievalRules.intentRules.length === 0" class="agent-runtime-empty">
            No intent rules.
          </p>
        </div>
      </div>

      <div v-else-if="ruleTab === 'chunk'" class="agent-runtime-rule-grid">
        <form class="agent-runtime-rule-form" @submit.prevent="saveRule('chunk')">
          <label>
            <span>Chunk Type</span>
            <input v-model.trim="ruleForms.chunk.chunkType" type="text" placeholder="troubleshooting" />
          </label>
          <label>
            <span>Keywords</span>
            <textarea v-model.trim="ruleForms.chunk.keywords" rows="3" placeholder="incident, retry, root cause"></textarea>
          </label>
          <label class="agent-runtime-rule-wide">
            <span>Regex</span>
            <input v-model.trim="ruleForms.chunk.pattern" type="text" placeholder="\\b(ERROR|WARN)\\b" />
          </label>
          <label>
            <span>Weight</span>
            <input v-model.number="ruleForms.chunk.weight" type="number" min="1" />
          </label>
          <label>
            <span>Priority</span>
            <input v-model.number="ruleForms.chunk.priority" type="number" />
          </label>
          <label class="agent-runtime-rule-check">
            <input v-model="ruleForms.chunk.enabled" type="checkbox" />
            <span>Enabled</span>
          </label>
          <div class="agent-runtime-rule-actions">
            <button type="submit" :disabled="rulesLoading" title="Save chunk type rule">
              <Save :size="16" stroke-width="2" />
              <span>{{ ruleForms.chunk.id ? "Update" : "Add" }}</span>
            </button>
            <button type="button" title="New chunk type rule" @click="resetRuleForm('chunk')">
              <Plus :size="16" stroke-width="2" />
              <span>New</span>
            </button>
          </div>
        </form>

        <div class="agent-runtime-rule-list">
          <article v-for="rule in retrievalRules.chunkTypeRules" :key="`chunk-${rule.id}`">
            <button type="button" title="Edit rule" @click="editRule('chunk', rule)">
              <strong>v{{ rule.version || 1 }} · {{ rule.chunkType }}</strong>
              <span>{{ rule.keywords || rule.pattern || "-" }}</span>
            </button>
            <small>W{{ rule.weight || 1 }} P{{ rule.priority || 0 }} {{ rule.enabled ? "ON" : "OFF" }}</small>
            <button type="button" title="Delete rule" @click="deleteRule('chunk', rule)">
              <Trash2 :size="15" stroke-width="2" />
            </button>
          </article>
          <p v-if="!rulesLoading && retrievalRules.chunkTypeRules.length === 0" class="agent-runtime-empty">
            No chunk type rules.
          </p>
        </div>
      </div>

      <div v-else class="agent-runtime-rule-grid">
        <form class="agent-runtime-rule-form" @submit.prevent="saveRule('expand')">
          <label>
            <span>Intent</span>
            <input v-model.trim="ruleForms.expand.intent" type="text" placeholder="optional" />
          </label>
          <label>
            <span>Source</span>
            <input v-model.trim="ruleForms.expand.sourceWord" type="text" placeholder="login" />
          </label>
          <label>
            <span>Expand Words</span>
            <textarea v-model.trim="ruleForms.expand.expandWords" rows="3" placeholder="auth, signin, access"></textarea>
          </label>
          <label>
            <span>Weight</span>
            <input v-model.number="ruleForms.expand.weight" type="number" min="1" />
          </label>
          <label>
            <span>Priority</span>
            <input v-model.number="ruleForms.expand.priority" type="number" />
          </label>
          <label class="agent-runtime-rule-check">
            <input v-model="ruleForms.expand.enabled" type="checkbox" />
            <span>Enabled</span>
          </label>
          <div class="agent-runtime-rule-actions">
            <button type="submit" :disabled="rulesLoading" title="Save expansion rule">
              <Save :size="16" stroke-width="2" />
              <span>{{ ruleForms.expand.id ? "Update" : "Add" }}</span>
            </button>
            <button type="button" title="New expansion rule" @click="resetRuleForm('expand')">
              <Plus :size="16" stroke-width="2" />
              <span>New</span>
            </button>
          </div>
        </form>

        <div class="agent-runtime-rule-list">
          <article v-for="rule in retrievalRules.expandRules" :key="`expand-${rule.id}`">
            <button type="button" title="Edit rule" @click="editRule('expand', rule)">
              <strong>v{{ rule.version || 1 }} · {{ rule.sourceWord || rule.intent || "global" }}</strong>
              <span>{{ rule.expandWords || "-" }}</span>
            </button>
            <small>W{{ rule.weight || 1 }} P{{ rule.priority || 0 }} {{ rule.enabled ? "ON" : "OFF" }}</small>
            <button type="button" title="Delete rule" @click="deleteRule('expand', rule)">
              <Trash2 :size="15" stroke-width="2" />
            </button>
          </article>
          <p v-if="!rulesLoading && retrievalRules.expandRules.length === 0" class="agent-runtime-empty">
            No expansion rules.
          </p>
        </div>
      </div>
    </section>

    <section class="agent-runtime-shell">
      <aside class="agent-runtime-runs" aria-label="Agent runs">
        <header>
          <div>
            <p>Runs</p>
            <strong>{{ filteredRuns.length }}</strong>
          </div>
          <button type="button" :disabled="!canPageBack" title="Previous page" @click="pageBack">
            <ChevronLeft :size="16" stroke-width="2" />
          </button>
          <button type="button" title="Next page" @click="pageForward">
            <ChevronRight :size="16" stroke-width="2" />
          </button>
        </header>

        <div class="agent-run-list">
          <div class="agent-run-list-head" aria-hidden="true">
            <span>Status</span>
            <span>Run</span>
            <span>Query</span>
            <span>Tenant</span>
            <span>User</span>
            <span>Updated</span>
            <span>Duration</span>
          </div>
          <button
            v-for="run in filteredRuns"
            :key="run.runId"
            :class="{ active: run.runId === selectedRunId }"
            type="button"
            @click="selectRun(run)"
          >
            <span :class="['agent-run-status', statusClass(run.status)]">{{ run.status || "UNKNOWN" }}</span>
            <strong>{{ shortId(run.runId) }}</strong>
            <small>{{ run.request?.query || run.errorMessage || "No query" }}</small>
            <span>{{ run.request?.tenantId || "-" }}</span>
            <span>{{ run.request?.userId || "-" }}</span>
            <time>{{ formatTime(updatedAt(run)) }}</time>
            <span>{{ formatDuration(durationMs(run)) }}</span>
          </button>
          <p v-if="!loading && filteredRuns.length === 0" class="agent-runtime-empty">No runs found.</p>
        </div>
      </aside>

      <main v-if="selectedRun || !embedded" class="agent-runtime-detail" aria-label="Run timeline">
        <header class="agent-runtime-detail-head">
          <div>
            <p>{{ selectedRun ? shortId(selectedRun.runId) : "No run selected" }}</p>
            <h2>{{ selectedRun?.request?.query || selectedRun?.errorMessage || "Runtime timeline" }}</h2>
          </div>
          <div class="agent-runtime-run-actions">
            <button
              type="button"
              :disabled="!selectedRun || !isActiveRun(selectedRun)"
              title="Stream events"
              @click="toggleStream"
            >
              <PauseCircle v-if="streamActive" :size="16" stroke-width="2" />
              <PlayCircle v-else :size="16" stroke-width="2" />
              <span>{{ streamActive ? "Stop" : "Stream" }}</span>
            </button>
            <button
              type="button"
              :disabled="!selectedRun || !isActiveRun(selectedRun) || isCancelling(selectedRun)"
              title="Cancel run"
              @click="cancelRun(selectedRun)"
            >
              <XCircle :size="16" stroke-width="2" />
              <span>{{ isCancelling(selectedRun) ? "Cancelling" : "Cancel" }}</span>
            </button>
          </div>
        </header>

        <section v-if="selectedRun" class="agent-runtime-run-summary">
          <dl>
            <div>
              <dt>Status</dt>
              <dd :class="statusClass(selectedRun.status)">{{ selectedRun.status }}</dd>
            </div>
            <div>
              <dt>Tenant</dt>
              <dd>{{ selectedRun.request?.tenantId || "-" }}</dd>
            </div>
            <div>
              <dt>User</dt>
              <dd>{{ selectedRun.request?.userId || "-" }}</dd>
            </div>
            <div>
              <dt>Duration</dt>
              <dd>{{ formatDuration(durationMs(selectedRun)) }}</dd>
            </div>
          </dl>
          <p v-if="streamState.message" :class="['agent-runtime-stream-note', streamState.kind]">
            {{ streamState.message }}
          </p>
        </section>

        <nav class="agent-runtime-tabs" aria-label="Timeline tabs">
          <button
            v-for="tab in detailTabs"
            :key="tab.key"
            :class="{ active: activeDetailTab === tab.key }"
            type="button"
            @click="activeDetailTab = tab.key"
          >
            <component :is="tab.icon" :size="15" stroke-width="2" />
            <span>{{ tab.label }}</span>
            <strong>{{ tab.count }}</strong>
          </button>
        </nav>

        <section class="agent-runtime-detail-body">
          <p v-if="timelineLoading" class="agent-runtime-empty">Loading timeline...</p>
          <p v-else-if="!selectedRun" class="agent-runtime-empty">Select a run to inspect.</p>

          <div v-else-if="activeDetailTab === 'timeline'" class="agent-runtime-timeline">
            <article v-for="item in timelineItems" :key="item.key" :class="item.kind">
              <span></span>
              <div>
                <header>
                  <strong>{{ item.title }}</strong>
                  <time>{{ formatTime(item.at) }}</time>
                </header>
                <p>{{ item.text }}</p>
                <pre v-if="item.payload">{{ formatJson(item.payload) }}</pre>
              </div>
            </article>
            <p v-if="timelineItems.length === 0" class="agent-runtime-empty">No timeline entries.</p>
          </div>

          <div v-else-if="activeDetailTab === 'trace'" class="agent-runtime-observation-list">
            <article v-if="trace">
              <header>
                <strong>{{ trace.contractVersion || "TRACE" }}</strong>
                <span>{{ trace.grounding?.status || "not_evaluated" }}</span>
              </header>
              <p>{{ trace.question || "-" }}</p>
              <dl>
                <div>
                  <dt>Answer</dt>
                  <dd>{{ trace.answer?.confidence || "-" }}</dd>
                </div>
                <div>
                  <dt>Evidence</dt>
                  <dd>{{ trace.evidence?.length || 0 }}</dd>
                </div>
                <div>
                  <dt>Tools</dt>
                  <dd>{{ trace.toolCalls?.length || 0 }}</dd>
                </div>
              </dl>
              <pre v-if="trace.failureReasons?.length">{{ formatJson(trace.failureReasons) }}</pre>
            </article>

            <article v-for="tool in trace?.toolCalls || []" :key="`trace-tool-${tool.step}-${tool.toolName}`">
              <header>
                <strong>Tool #{{ tool.step || "-" }} {{ tool.displayName || tool.toolName || "tool" }}</strong>
                <span>{{ tool.governance?.policyDecision || (tool.success === false ? "failed" : tool.success === true ? "success" : "recorded") }}</span>
              </header>
              <p>
                {{ tool.errorMessage || tool.outputPreview || "-" }}
              </p>
              <p v-if="tool.governance?.auditId">
                audit {{ tool.governance.auditId }} 路 {{ tool.governance.riskLevel || "-" }}
                {{ tool.governance.confirmRequired ? "/ confirm required" : "" }}
              </p>
              <pre v-if="hasPayload(tool.input)">{{ formatJson(tool.input) }}</pre>
              <pre v-if="hasPayload(tool.governance)">{{ formatJson(tool.governance) }}</pre>
            </article>

            <article v-for="item in trace?.evidence || []" :key="`trace-evidence-${item.refId}`">
              <header>
                <strong>{{ item.evidenceType || "EVIDENCE" }}</strong>
                <span>{{ item.citationUsed ? "cited" : "unused" }}</span>
              </header>
              <p>{{ item.refId }} {{ item.source ? `- ${item.source}` : "" }}</p>
              <p>{{ item.contentPreview || "-" }}</p>
              <pre v-if="hasPayload(item.metadata)">{{ formatJson(item.metadata) }}</pre>
            </article>

            <article v-if="trace?.answer">
              <header>
                <strong>Answer</strong>
                <span>{{ trace.answer.contractVersion || "-" }}</span>
              </header>
              <p>{{ trace.answer.answer || "-" }}</p>
              <pre>{{ formatJson(trace.answer) }}</pre>
            </article>

            <article v-if="trace?.grounding">
              <header>
                <strong>Grounding</strong>
                <span>{{ trace.grounding.status || "not_evaluated" }}</span>
              </header>
              <pre>{{ formatJson(trace.grounding) }}</pre>
            </article>

            <p v-if="!trace" class="agent-runtime-empty">No trace.</p>
          </div>

          <div v-else-if="activeDetailTab === 'events'" class="agent-runtime-event-list">
            <article v-for="event in events" :key="event.eventId || `${event.type}-${event.createdAt}`">
              <header>
                <strong>{{ event.type || "EVENT" }}</strong>
                <time>{{ formatTime(event.createdAt) }}</time>
              </header>
              <p>{{ event.message || "-" }}</p>
              <pre v-if="hasPayload(event.payload)">{{ formatJson(event.payload) }}</pre>
            </article>
            <p v-if="events.length === 0" class="agent-runtime-empty">No events.</p>
          </div>

          <div v-else-if="activeDetailTab === 'steps'" class="agent-runtime-step-list">
            <article v-for="step in steps" :key="step.step">
              <header>
                <strong>#{{ step.step }} {{ step.action || "STEP" }}</strong>
                <time>{{ formatTime(step.plannedAt) }}</time>
              </header>
              <dl>
                <div>
                  <dt>Tool</dt>
                  <dd>{{ step.resolvedToolName || step.toolName || "-" }}</dd>
                </div>
                <div>
                  <dt>Observations</dt>
                  <dd>{{ step.observationCount || 0 }}</dd>
                </div>
              </dl>
              <p>{{ step.reason || step.answerPreview || "-" }}</p>
              <pre v-if="hasPayload(step.executionPlan)">{{ formatJson(step.executionPlan) }}</pre>
            </article>
            <p v-if="steps.length === 0" class="agent-runtime-empty">No steps.</p>
          </div>

          <div v-else class="agent-runtime-observation-list">
            <article v-for="(observation, index) in observations" :key="`${observation.type}-${index}`">
              <header>
                <strong>{{ observation.type || "OBSERVATION" }}</strong>
                <span>{{ observation.source || "-" }}</span>
              </header>
              <p>{{ observation.content || "-" }}</p>
              <pre v-if="hasPayload(observation.metadata)">{{ formatJson(observation.metadata) }}</pre>
            </article>
            <p v-if="observations.length === 0" class="agent-runtime-empty">No observations.</p>
          </div>
        </section>
      </main>
    </section>
  </section>
</template>

<script src="../js/views/AgentRuntimeView.js"></script>
