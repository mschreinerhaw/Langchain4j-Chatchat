<template>
  <section class="feature-view ai-search-view search-workspace">
    <header>
      <p>
        <a href="#" class="search-title-link" @click.prevent="goToLibrary">投研资料库</a>
      </p>
      <h1>面向投研的信息检索</h1>
    </header>

    <div class="search-panel">
      <input
        v-model="keyword"
        placeholder="搜索公司、行业、公告或宏观事件"
        @keyup.enter="performSearch"
      >
      <button type="button" class="upload-trigger" @click="openUploadDialog">上传</button>
      <button type="button" :disabled="loading" @click="performSearch">
        {{ loading ? "搜索中" : "搜索" }}
      </button>
    </div>

    <section v-if="searched || loading" class="inline-results">
      <p v-if="loading" class="search-empty">正在检索资料库...</p>
      <p v-else-if="error" class="search-error">{{ error }}</p>
      <p v-else-if="results.length === 0" class="search-empty">
        {{ resultMessage || "没有找到匹配资料。请先上传资料，或换一个关键词再搜索。" }}
      </p>

      <div v-else class="results-list">
        <p class="results-count">
          找到 {{ resultTotal }} 条资料 · 当前显示 {{ pageStart }}-{{ pageEnd }} 条 · {{ searchTookMs }} ms
          <span v-if="hasMoreResults"> · 已载入前 {{ results.length }} 条</span>
        </p>
        <article v-for="result in pagedResults" :key="result.docId" class="search-result-item">
          <h3>{{ result.title }}</h3>
          <div class="result-meta">{{ result.source }} · {{ result.date }}</div>
          <p>{{ result.summary }}</p>
          <div v-if="result.matchedKeywords?.length" class="result-tags">
            <span v-for="term in result.matchedKeywords" :key="term">{{ term }}</span>
          </div>
        </article>

        <nav v-if="resultTotal > pageSize" class="search-pagination" aria-label="AI搜索结果分页">
          <span>第 {{ page }} / {{ pageCount }} 页，每页 {{ pageSize }} 条</span>
          <div>
            <button type="button" :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
            <button
              v-for="pageNumber in pageButtons"
              :key="pageNumber"
              type="button"
              :class="{ active: pageNumber === page }"
              @click="goPage(pageNumber)"
            >
              {{ pageNumber }}
            </button>
            <button type="button" :disabled="page >= pageCount" @click="goPage(page + 1)">下一页</button>
          </div>
        </nav>
      </div>
    </section>

    <div v-if="showUploadDialog" class="upload-dialog-backdrop" @click.self="closeUploadDialog">
      <form class="upload-dialog" @submit.prevent="uploadDocument">
        <header>
          <div>
            <p>资料上传</p>
            <h2>上传投研资料</h2>
          </div>
          <button type="button" class="dialog-close" :disabled="uploading" @click="closeUploadDialog">×</button>
        </header>

        <div class="file-picker">
          <input
            ref="uploadFile"
            type="file"
            accept=".txt,.md,.csv,.pdf,.doc,.docx,.xls,.xlsx"
            @change="handleFileChange"
          >
          <button type="button" class="file-picker-button" @click="triggerFilePicker">选择文件</button>
          <span>{{ uploadForm.file?.name || "未选择文件，最大 5MB" }}</span>
        </div>

        <input v-model="uploadForm.title" placeholder="资料标题">
        <input v-model="uploadForm.source" placeholder="资料来源">
        <input v-model="uploadForm.date" type="date">
        <select v-model="uploadForm.documentType" class="document-type-select">
          <option v-for="option in documentTypeOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <input v-model="uploadForm.tags" placeholder="标签，多个用逗号分隔">

        <p v-if="uploadError" class="search-error">{{ uploadError }}</p>

        <footer>
          <button type="button" class="secondary-button" :disabled="uploading" @click="closeUploadDialog">取消</button>
          <button type="submit" class="primary-button" :disabled="uploading">
            {{ uploading ? "上传中" : "上传资料" }}
          </button>
        </footer>
      </form>
    </div>
  </section>
</template>

<script src="../js/views/AiSearchView.js"></script>
