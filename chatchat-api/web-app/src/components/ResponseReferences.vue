<template>
  <section v-if="hasDetails" class="response-references" :class="{ compact }">
    <details v-if="sources.length" :open="compact">
      <summary>数据来源</summary>
      <article v-for="source in sources" :key="source.rank + source.source" class="reference-row">
        <strong>
          #{{ source.rank || "-" }}
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

    <details v-if="toolTraceRows.length" :open="compact">
      <summary>调用工具</summary>
      <article v-for="trace in toolTraceRows" :key="trace.toolName + trace.startedAt" class="reference-row">
        <strong>{{ trace.displayName || trace.toolName || "工具调用" }}</strong>
        <p>{{ trace.success === false ? "调用失败" : "调用成功" }}</p>
        <p v-if="trace.errorText" class="tool-error">{{ trace.errorText }}</p>
        <button
          v-if="trace.webPages.length"
          type="button"
          class="web-search-pages-trigger"
          @click="openWebPagesDialog(trace)"
        >
          查询网页（{{ trace.webPages.length }}）
        </button>
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
