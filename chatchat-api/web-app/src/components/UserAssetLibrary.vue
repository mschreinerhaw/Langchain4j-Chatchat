<template>
  <details class="user-asset-library" @toggle="opened">
    <summary>我的文件与分析记录</summary>
    <p>保存原始资料，选择本次分析需要的文件；历史资料仅在勾选后使用。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div class="asset-actions">
      <label
        >上传资料
        <input
          type="file"
          accept=".pdf,.docx,.xlsx,.csv,.parquet,.txt,.md,.json,.jsonl"
          :disabled="busy"
          @change="upload"
      /></label>
      <button :disabled="busy" @click="refresh">刷新</button>
      <span v-if="space">已用 {{ size(space.usedBytes) }} / {{ size(space.quotaBytes) }}</span>
    </div>
    <form v-if="knowledgeEnabled" class="asset-actions" @submit.prevent="searchKnowledge">
      <label
        >搜索我的资料与分析历史
        <input v-model="query" maxlength="2000" placeholder="输入主题或关键词"
      /></label>
      <label><input v-model="historyOnly" type="checkbox" />仅分析历史</label>
      <button :disabled="busy || !query.trim()">搜索</button>
      <button type="button" :disabled="busy" @click="rebuildKnowledge">重建我的索引</button>
    </form>
    <ul v-if="searchResults.length" aria-label="资料检索结果">
      <li v-for="hit in searchResults" :key="hit.fileId">
        <div
          ><strong>{{ hit.fileName }}</strong
          ><p>{{ hit.excerpt }}</p>
          <small v-if="hit.kind === 'CONTEXT'"
            >历史模型分析，使用前需核对来源与验证结果。</small
          ></div
        >
        <button :disabled="busy" @click="selectHit(hit)">选入本次分析</button>
      </li>
    </ul>
    <ul class="asset-files">
      <li v-for="file in files" :key="file.id">
        <label
          ><input
            v-model="selected"
            type="checkbox"
            :value="file.id"
            :disabled="busy || file.state !== 'AVAILABLE' || !selectable(file)"
          />
          {{ file.fileName }} · {{ size(file.size) }}
          <small
            >{{ label(file.kind) }}{{ file.state === "STAGED" ? " · 上传待重试" : "" }}</small
          ></label
        >
        <small v-if="indexStates[file.id]">{{ indexLabel(indexStates[file.id].state) }}</small>
        <button
          v-if="['FAILED', 'SKIPPED'].includes(indexStates[file.id]?.state)"
          :disabled="busy"
          @click="retryIndex(file)"
          >重试索引</button
        >
        <button :disabled="busy || file.state !== 'AVAILABLE'" @click="download(file)">下载</button>
        <button v-if="!file.taskId" :disabled="busy" @click="remove(file)">删除</button>
      </li>
    </ul>
    <p v-if="!files.length">暂无文件。支持 PDF、Word、Excel、CSV 和 Parquet 等资料。</p>
    <label
      >分析模型<select v-model="modelName" :disabled="busy"
        ><option v-for="model in models" :key="model.value" :value="model.value">{{
          model.label
        }}</option></select
      ></label
    >
    <label
      >分析目标<textarea
        v-model="instruction"
        :disabled="busy"
        placeholder="说明需要计算的指标、口径和希望得到的结果"
      ></textarea>
    </label>
    <button :disabled="busy || !selected.length || !instruction.trim()" @click="analyze">{{
      busy ? "正在处理…" : "分析选中文件"
    }}</button>
    <p v-if="status" role="status">{{ status }}</p>
    <pre v-if="report" class="asset-report">{{ report }}</pre>
  </details>
</template>
<script src="../js/components/UserAssetLibrary.js"></script>
<style scoped src="../styles/components/user-asset-library.css"></style>
