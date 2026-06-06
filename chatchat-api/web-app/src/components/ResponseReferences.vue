<template>
  <section v-if="hasDetails" class="response-references">
    <details v-if="sources.length">
      <summary>来源引用</summary>
      <article v-for="source in sources" :key="source.rank + source.source" class="reference-row">
        <strong>#{{ source.rank || "-" }} {{ source.source || "来源" }}</strong>
        <p>{{ source.snippet || source.content || "暂无摘要" }}</p>
      </article>
    </details>

    <details v-if="toolTraces.length">
      <summary>工具轨迹</summary>
      <article v-for="trace in toolTraces" :key="trace.toolName + trace.startedAt" class="reference-row">
        <strong>{{ trace.displayName || trace.toolName || "工具调用" }}</strong>
        <p>{{ trace.success === false ? "调用失败" : "调用成功" }}</p>
      </article>
    </details>
  </section>
</template>

<script>
export default {
  name: "ResponseReferences",
  props: {
    sources: {
      type: Array,
      default: () => []
    },
    toolTraces: {
      type: Array,
      default: () => []
    }
  },
  computed: {
    hasDetails() {
      return this.sources.length || this.toolTraces.length;
    }
  }
};
</script>
