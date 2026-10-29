<template>
  <div class="sql-assistant-message">
    <template v-for="(part,index) in parts" :key="index">
      <div v-if="part.type === 'markdown'" class="sql-ai-markdown" v-html="renderReportNarrative(part.content)"></div>
      <section v-else class="sql-assistant-code" aria-label="AI SQL 代码">
        <header><span>SQL</span><div>
          <button type="button" @click="copy(part.content,index)"><Copy :size="12" />{{ copied === index ? '已复制' : '复制' }}</button>
          <button type="button" :disabled="!insertable" @click="$emit('insert',part.content)">插入编辑器</button>
          <button type="button" :disabled="!replaceable" @click="$emit('replace',part.content)">替换选中 SQL</button>
        </div></header>
        <pre><code class="language-sql" v-html="highlightAssistantSql(part.content)"></code></pre>
      </section>
    </template>
    <small v-if="copyError" role="alert">{{ copyError }}</small>
  </div>
</template>
<script src="../../js/components/sql-studio/SqlAssistantMessage.js"></script>
<style scoped src="../../styles/components/sql-studio/SqlAssistantMessage.css"></style>
