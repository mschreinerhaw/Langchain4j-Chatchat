<template>
  <details class="sql-user-documents">
    <summary
      >文档与参考脚本 <small>{{ modelValue.length }} 个已选</small></summary
    >
    <p>选择本次生成要引用的文档，并指定用途。</p>
    <div class="sql-document-actions"
      ><input
        ref="uploadInput"
        class="sql-document-file-input"
        aria-label="上传用户空间文档"
        type="file"
        multiple
        :accept="accept"
        :disabled="busy || uploading"
        @change="uploadFiles" /><button
        type="button"
        :disabled="busy || uploading"
        @click="uploadInput?.click()"
        >上传文档</button
      ><button type="button" :disabled="busy || uploading || loading" @click="refresh"
        >选择已有文档</button
      ><slot name="actions"
    /></div>
    <slot />
    <input v-model="query" type="search" aria-label="搜索用户空间文档" placeholder="搜索文档名称" />
    <p v-if="uploading" role="status">正在保存到用户空间…</p
    ><p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && !documents.length"
      >暂无可用文档，可上传规范、字段说明、分析资料或 SQL 文件。</p
    >
    <p v-for="item in unavailable" :key="item.fileId" role="alert"
      >{{ item.fileName || item.fileId }} 已不可用。<button
        type="button"
        :disabled="busy || uploading"
        @click="remove(item.fileId)"
        >取消引用</button
      ></p
    >
    <div v-for="file in visible" :key="file.id" class="sql-user-document">
      <label
        ><input
          type="checkbox"
          :aria-label="'引用文档 ' + file.fileName"
          :checked="selected(file.id)"
          :disabled="busy || uploading"
          @change="toggle(file)"
        /><span :title="file.fileName">{{ file.fileName }}</span></label
      >
      <select
        v-if="selected(file.id)"
        :aria-label="'文档用途 ' + file.fileName"
        :value="selected(file.id).purpose"
        :disabled="busy || uploading"
        @change="setPurpose(file.id, $event.target.value)"
        ><option value="RULES">参考规则</option
        ><option value="EVIDENCE">分析依据</option></select
      >
    </div>
    <small>最多 8 个文档、合计 16 MiB。取消勾选保留用户空间原件。</small>
  </details>
</template>
<script src="../../js/components/sql-studio/SqlUserDocuments.js"></script>
<style scoped src="../../styles/components/sql-studio/sql-user-documents.css"></style>
