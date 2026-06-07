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
        <div v-if="trace.webPages.length" class="web-search-pages">
          <span>查询网页</span>
          <ol>
            <li v-for="page in trace.webPages" :key="page.rank + page.url + page.title">
              <a
                v-if="page.url"
                :href="page.url"
                target="_blank"
                rel="noopener noreferrer"
              >
                {{ page.title }}
              </a>
              <strong v-else>{{ page.title }}</strong>
              <small v-if="page.url">{{ page.url }}</small>
              <p v-if="page.snippet">{{ page.snippet }}</p>
            </li>
          </ol>
        </div>
      </article>
    </details>
  </section>
</template>

<script src="../js/components/ResponseReferences.js"></script>
