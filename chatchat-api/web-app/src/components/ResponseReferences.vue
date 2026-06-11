<template>
  <section v-if="hasDetails" class="response-references" :class="{ compact }">
    <details v-if="sources.length" :open="compact">
      <summary>内部文档来源（{{ sources.length }}）</summary>
      <article v-for="source in sources" :key="source.rank + source.source" class="reference-row">
        <strong>
          <span class="reference-badge">文档 {{ source.rank || "-" }}</span>
          <a
            v-if="sourceUrl(source)"
            :href="sourceUrl(source)"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ source.source || source.title || "来源" }}
          </a>
          <span v-else>{{ source.source || source.title || "来源" }}</span>
        </strong>
        <p>{{ source.snippet || source.content || "暂无摘要" }}</p>
      </article>
    </details>

    <details v-if="documentPageRows.length" :open="compact">
      <summary>引用文档（{{ documentPageRows.length }}）</summary>
      <article
        v-for="(page, index) in documentPageRows"
        :key="page.docId + page.url + page.title + index"
        class="reference-row document-reference-row"
      >
        <strong>
          <span class="reference-badge">文档 {{ page.rank || index + 1 }}</span>
          <a
            v-if="page.url"
            :href="page.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ page.title || page.docId || "引用文档" }}
          </a>
          <span v-else>{{ page.title || page.docId || "引用文档" }}</span>
        </strong>
        <small v-if="page.docId">{{ page.docId }}</small>
        <small v-else-if="page.url">{{ displayUrl(page.url) }}</small>
        <p>{{ page.snippet || "暂无摘要" }}</p>
      </article>
    </details>

    <details v-if="webPageRows.length" :open="compact">
      <summary>网络搜索引用（{{ webPageRows.length }}）</summary>
      <article v-for="(page, index) in webPageRows" :key="page.rank + page.url + page.title" class="reference-row web-reference-row">
        <strong>
          <span class="reference-badge web">引用 {{ page.rank || index + 1 }}</span>
          <a
            v-if="page.url"
            :href="page.url"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ page.title || page.url }}
          </a>
          <span v-else>{{ page.title || "引用" }}</span>
        </strong>
        <small v-if="page.url">{{ displayUrl(page.url) }}</small>
        <p>{{ page.snippet || "暂无摘要" }}</p>
      </article>
    </details>

    <details v-if="toolTraceRows.length" :open="compact && !webPageRows.length">
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
      @click.self="closeWebPagesDialog"
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
            class="web-pages-modal-close"
            aria-label="关闭"
            @click="closeWebPagesDialog"
          >
            x
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
