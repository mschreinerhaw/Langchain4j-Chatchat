<template>
  <section v-if="hasDetails" class="response-references" :class="{ compact }">
    <details v-if="webPageRows.length" class="citation-source-details">
      <summary>
        <span>引用来源</span>
        <small>{{ webPageRows.length }} 个网站</small>
      </summary>
      <article
        v-for="(page, index) in webPageRows"
        :key="page.rank + page.url + page.title"
        class="citation-source-row"
      >
        <a
          :href="page.url"
          target="_blank"
          rel="noopener noreferrer"
          :title="page.title || page.url"
        >
          <span class="citation-source-site">{{ page.publisher || displayUrl(page.url) || `网站 ${index + 1}` }}</span>
          <span class="citation-source-title">{{ page.title || "引用网站" }}</span>
          <small>{{ page.url }}</small>
        </a>
      </article>
    </details>

    <details v-if="evidencePremiseRows.length">
      <summary>证据链条（{{ evidencePremiseRows.length }}）</summary>
      <article
        v-for="(premise, index) in evidencePremiseRows"
        :key="premise.rank + premise.text + index"
        class="reference-row evidence-premise-row"
      >
        <strong>
          <span class="reference-badge">依据 {{ premise.rank || index + 1 }}</span>
        </strong>
        <p>{{ premise.text }}</p>
      </article>
    </details>
    <details v-if="documentReferenceRows.length">
      <summary>引用文档（{{ documentReferenceRows.length }}）</summary>
      <article
        v-for="(source, index) in documentReferenceRows"
        :key="source.docId + source.url + source.title + index"
        class="reference-row document-reference-row"
      >
        <strong>
          <span class="reference-badge">文档 {{ source.rank || index + 1 }}</span>
          <a
            v-if="source.url"
            :href="source.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ source.title || source.docId || "引用文档" }}
          </a>
          <span v-else>{{ source.title || source.docId || "引用文档" }}</span>
        </strong>
        <small v-if="source.url">{{ displayUrl(source.url) }}</small>
        <p>{{ source.snippet || "暂无摘要" }}</p>
      </article>
    </details>

    <details v-if="toolTraceRows.length">
      <summary>调用工具</summary>
      <article v-for="trace in toolTraceRows" :key="trace.toolName + trace.startedAt" class="reference-row">
        <strong>{{ trace.displayName || trace.toolName || "工具调用" }}</strong>
        <p>{{ trace.statusText }}</p>
        <p v-if="trace.errorText" class="tool-error">{{ trace.errorText }}</p>
      </article>
    </details>

    <div
      v-if="webPagesDialog.open"
      class="web-pages-modal-backdrop"
    >
      <section
        class="web-pages-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="web-pages-modal-title"
      >
        <header>
          <h3 id="web-pages-modal-title">{{ webPagesDialog.title }}</h3>
          <button
            type="button"
            class="app-dialog-close"
            aria-label="关闭"
            title="关闭"
            @click="closeWebPagesDialog"
          >
            ×
          </button>
        </header>
        <ol class="web-pages-modal-list">
          <li v-for="page in webPagesDialog.pages" :key="page.rank + page.url + page.title">
            <a
              v-if="page.url"
              :href="page.url"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ page.title }}
            </a>
            <strong v-else>{{ page.title }}</strong>
            <small v-if="page.url">{{ displayUrl(page.url) }}</small>
            <p v-if="page.snippet">{{ page.snippet }}</p>
          </li>
        </ol>
      </section>
    </div>
  </section>
</template>

<script src="../js/components/ResponseReferences.js"></script>
