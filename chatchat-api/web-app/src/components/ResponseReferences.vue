<template>
  <section v-if="hasDetails || actionsAvailable" class="response-references" :class="{ compact }">
    <footer class="reference-toolbar" aria-label="回答操作与来源">
      <button
        v-if="hasDetails"
        type="button"
        class="source-panel-toggle"
        :class="{ active: sourcePanelOpen }"
        :aria-expanded="sourcePanelOpen"
        @click="toggleSourcePanel"
      >
        来源<span v-if="allSourceRows.length" class="source-count">{{ allSourceRows.length }}</span>
      </button>
      <slot name="actions" />
    </footer>
    <aside
      v-if="sourcePanelOpen"
      class="answer-source-panel"
      aria-label="回答来源侧栏"
      @keydown.esc="toggleSourcePanel"
    >
      <header>
        <div
          ><strong>回答来源</strong
          ><small>{{ allSourceRows.length }} 个来源 · 点击标题查看原文</small></div
        >
        <button type="button" aria-label="关闭来源侧栏" @click="toggleSourcePanel">×</button>
      </header>
      <ol class="answer-source-list">
        <li
          v-for="source in presentationSources"
          :key="source.kind + source.index + source.title"
          class="answer-source-item"
        >
          <div class="answer-source-meta"
            ><span>{{ source.index }} · {{ source.label }}</span
            ><time v-if="source.date">{{ source.date }}</time></div
          >
          <a
            v-if="source.url"
            class="answer-source-link"
            :href="source.url"
            :target="source.url.startsWith('http') ? '_blank' : undefined"
            rel="noopener noreferrer"
            >{{ source.title }}</a
          >
          <span v-else class="answer-source-title">{{ source.title }}</span>
          <p v-if="source.snippet" class="answer-source-excerpt">{{ source.snippet }}</p>
          <small v-if="source.location" class="answer-source-location">{{ source.location }}</small>
        </li>
      </ol>
      <div v-if="knowledgeSummary" class="knowledge-runtime-summary">
        <strong>已应用 Agent 领域知识</strong>
        <span>{{ knowledgeSummary }}</span>
      </div>
      <section v-if="conflictRows.length" class="source-conflicts" aria-label="冲突来源">
        <strong>发现 {{ conflictRows.length }} 组冲突来源</strong>
        <article v-for="conflict in conflictRows" :key="conflict.id">
          <b>{{ conflict.claim || "同一事实存在不同说法" }}</b>
          <p v-if="conflict.left">来源 A：{{ conflict.left }}</p>
          <p v-if="conflict.right">来源 B：{{ conflict.right }}</p>
          <small v-if="conflict.resolution">处理：{{ conflict.resolution }}</small>
        </article>
      </section>
      <details v-if="evidencePremiseRows.length">
        <summary>证据链条（{{ evidencePremiseRows.length }}）</summary>
        <article
          v-for="(premise, index) in evidencePremiseRows"
          :key="premise.rank + premise.text + index"
          class="reference-row evidence-premise-row"
        >
          <strong
            ><span class="reference-badge">依据 {{ premise.rank || index + 1 }}</span></strong
          ><p>{{ premise.text }}</p>
        </article>
      </details>
      <details v-if="toolTraceRows.length">
        <summary>调用工具</summary>
        <article
          v-for="trace in toolTraceRows"
          :key="trace.toolName + trace.startedAt"
          class="reference-row"
        >
          <strong>{{ trace.displayName || trace.toolName || "工具调用" }}</strong
          ><p>{{ trace.statusText }}</p>
          <p v-if="trace.errorText" class="tool-error">{{ trace.errorText }}</p>
        </article>
      </details>
    </aside>
    <div v-if="webPagesDialog.open" class="web-pages-modal-backdrop">
      <section
        class="web-pages-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="web-pages-modal-title"
      >
        <header
          ><h3 id="web-pages-modal-title">{{ webPagesDialog.title }}</h3
          ><button
            type="button"
            class="app-dialog-close"
            aria-label="关闭"
            @click="closeWebPagesDialog"
            >×</button
          ></header
        >
        <ol class="web-pages-modal-list"
          ><li v-for="page in webPagesDialog.pages" :key="page.rank + page.url + page.title">
            <a v-if="page.url" :href="page.url" target="_blank" rel="noopener noreferrer">{{
              page.title
            }}</a
            ><strong v-else>{{ page.title }}</strong>
            <small v-if="page.url">{{ displayUrl(page.url) }}</small
            ><p v-if="page.snippet">{{ page.snippet }}</p>
          </li></ol
        >
      </section>
    </div>
  </section>
</template>

<script src="../js/components/ResponseReferences.js"></script>
