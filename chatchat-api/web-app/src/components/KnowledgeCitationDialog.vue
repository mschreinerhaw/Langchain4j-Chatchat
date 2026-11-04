<template>
  <div class="knowledge-source-overlay" @click.self="$emit('close')">
    <section class="knowledge-source-dialog" role="dialog" aria-modal="true" aria-labelledby="knowledge-source-title">
      <header>
        <h3 id="knowledge-source-title">{{ source?.title || '引用原文' }}</h3>
        <button type="button" class="knowledge-source-close" aria-label="关闭原文" title="关闭" @click="$emit('close')">
          <X :size="18" :stroke-width="1.8" aria-hidden="true" />
        </button>
      </header>
      <p v-if="loading">正在核验来源权限与版本…</p>
      <p v-else-if="error" role="alert">{{ error }}</p>
      <template v-else-if="source">
        <p>版本 {{ source.version }} · {{ source.section_path || '文档信息' }} · 分块 {{ source.chunk_index }}</p>
        <template v-if="source.resource_id === 'financial_news'">
          <p>发布时间：{{ source.published_at || '来源未提供' }}<br />采集时间：{{ source.collected_at || '来源未提供' }}</p>
          <p v-if="source.source_metadata?.source_view === 'summary'" class="knowledge-source-note">此引用为已索引摘要，不能替代阅读全文。</p>
        </template>
        <template v-else-if="source.resource_id === 'internet_search'">
          <p>发布时间：{{ source.published_at || '来源未提供' }}<br />获取时间：{{ source.retrieved_at || '来源未提供' }}</p>
          <p class="knowledge-source-note">{{ source.source_metadata?.source_view === 'PUBLIC_PAGE_EXTRACTED_TEXT' ? '公开网页提取文本；未执行网页脚本。' : '搜索结果摘要，不能替代阅读完整网页。' }}{{ source.source_metadata?.content_truncated ? ' 内容已按读取上限截断。' : '' }}</p>
        </template>
        <template v-else-if="source.resource_id === 'financial_market'">
          <p>数据截止：{{ source.data_as_of || '来源未提供' }}<br />采集时间：{{ source.collected_at || '来源未提供' }}</p>
          <p class="knowledge-source-note">已采集数据，不代表实时行情。单位、市场及复权口径以原记录为准，未声明时为未知。</p>
        </template>
        <p v-else class="knowledge-source-note">{{ source.page == null ? '原文索引未提供页码' : `第 ${source.page} 页` }}</p>
        <p v-if="sourceLink"><a :href="sourceLink" target="_blank" rel="noopener noreferrer">打开原始来源</a></p>
        <div v-if="marketRows.length" class="knowledge-source-table">
          <table><thead><tr><th v-for="column in marketColumns" :key="column">{{ column }}</th></tr></thead>
            <tbody><tr v-for="(row,index) in marketRows" :key="index"><td v-for="column in marketColumns" :key="column">{{ formatValue(row[column]) }}</td></tr></tbody>
          </table>
        </div>
        <details v-if="marketRows.length"><summary>查看完整来源记录与口径</summary><pre>{{ source.text }}</pre></details>
        <pre v-else>{{ source.text || '此引用仅定位文档信息，未引用正文片段。' }}</pre>
        <p v-if="source.resource_id" class="knowledge-source-note">来源许可依原始声明；未提供许可信息不代表可商业使用。</p>
        <p class="knowledge-source-note">已核验当前访问权限和原文版本。</p>
      </template>
    </section>
  </div>
</template>
<script src="../js/components/KnowledgeCitationDialog.js"></script>
<style scoped src="../styles/components/knowledge-citation-dialog.css"></style>
