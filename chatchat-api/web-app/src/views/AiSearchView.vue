<template>
  <section class="feature-view ai-search-view search-workspace">
    <header>
      <p>
        <a href="#" class="search-title-link" @click.prevent="goToLibrary">文档库</a>
      </p>
    </header>

    <div class="search-panel">
      <input
        v-model="keyword"
        placeholder="搜索文档标题、来源、标签或正文关键词"
        @keyup.enter="handleSearchAction"
      >
      <button type="button" class="upload-trigger" @click="openUploadDialog">上传</button>
      <button
        type="button"
        :class="{ 'search-stop-action': Boolean(searchController) }"
        :disabled="loading && !searchController"
        @click="handleSearchAction"
      >
        {{ searchController ? "停止" : (loading ? "处理中" : "检索") }}
      </button>
    </div>

    <section v-if="searched || loading" class="inline-results">
      <p v-if="searchController" class="search-empty">正在检索文档库，点击“停止”可终止本租户的当前检索...</p>
      <p v-else-if="error" class="search-error">{{ error }}</p>
      <p v-else-if="results.length === 0" class="search-empty">
        {{ resultMessage || "没有找到匹配文档。请先上传文档，或换一个关键词再检索。" }}
      </p>

      <div v-else class="results-list">
        <p class="results-count">
          找到 {{ resultTotal }} 条文档 · 当前显示 {{ pageStart }}-{{ pageEnd }} 条 · {{ searchTookMs }} ms
          <span v-if="hasMoreResults"> · 已载入前 {{ results.length }} 条</span>
        </p>
        <article v-for="result in pagedResults" :key="result.docId" class="search-result-item">
          <div class="result-head">
            <div>
              <h3>{{ result.title }}</h3>
              <div class="result-meta">{{ result.source }} · {{ result.date }}</div>
            </div>
            <div class="result-actions">
              <button
                type="button"
                :disabled="!canPreviewResult(result)"
                :title="documentPreviewTitle(result)"
                @click="openResult(result)"
              >
                查看内容
              </button>
              <button type="button" class="ask-ai-action" @click="askAiAboutResult(result)">问AI</button>
              <button type="button" class="danger-action" @click="removeDocument(result)">删除</button>
            </div>
          </div>
          <p>{{ result.summary }}</p>
          <div v-if="visibleResultTags(result).length" class="result-tags">
            <span v-for="tag in visibleResultTags(result)" :key="tag">{{ tag }}</span>
          </div>
        </article>

        <nav v-if="resultTotal > pageSize" class="search-pagination" aria-label="文档检索结果分页">
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

    <div v-if="showUploadDialog" class="upload-dialog-backdrop">
      <form class="upload-dialog" @submit.prevent="uploadDocument">
        <header>
          <div>
            <p>资料上传</p>
            <h2>上传文档</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" :disabled="uploading" @click="closeUploadDialog">×</button>
        </header>

        <div class="document-upload-modes" role="tablist" aria-label="文档导入方式">
          <button type="button" role="tab" :aria-selected="uploadMode === 'file'" :class="{ active: uploadMode === 'file' }" :disabled="uploading" @click="uploadMode = 'file'">本地文件</button>
          <button type="button" role="tab" :aria-selected="uploadMode === 'url'" :class="{ active: uploadMode === 'url' }" :disabled="uploading" @click="uploadMode = 'url'">网络地址</button>
        </div>

        <div v-if="uploadMode === 'file'" class="file-picker">
          <input
            ref="uploadFile"
            type="file"
            multiple
            accept=".txt,.md,.sql,.csv,.pdf,.doc,.docx,.xls,.xlsx"
            @change="handleFileChange"
          >
          <button type="button" class="file-picker-button" @click="triggerFilePicker">选择文件</button>
          <span>{{ uploadForm.files?.length > 1 ? `${uploadForm.files.length} 个文件` : (uploadForm.file?.name || "未选择文件，单文件最大 55MB") }}</span>
        </div>
        <p v-if="uploadMode === 'file'" class="upload-size-tip">超过 5MB 的文档仅支持单文件上传，后台将按 5MB 分片处理并建立索引。</p>
        <div v-else class="document-url-import">
          <label class="document-url-field"><span>文档地址 *</span><input v-model.trim="uploadUrl" type="url" required maxlength="2048" placeholder="https://example.com/report.pdf"><small>支持互联网或内网 HTTP API，单个文档最大 55MB。</small></label>
          <button type="button" class="document-http-toggle" :aria-expanded="uploadAdvancedOpen" aria-controls="document-http-options" @click="uploadAdvancedOpen = !uploadAdvancedOpen">
            <span>高级参数</span><ChevronDown :size="14" :class="{ expanded: uploadAdvancedOpen }" aria-hidden="true" />
          </button>
          <section v-if="uploadAdvancedOpen" id="document-http-options" class="document-http-options">
            <label><span>请求方法</span><select v-model="uploadHttpMethod"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option></select></label>
            <label><span>Query 参数（JSON）</span><textarea v-model="uploadQueryParams" rows="3" spellcheck="false" placeholder='{"version":"latest"}'></textarea></label>
            <label><span>请求头（JSON）</span><textarea v-model="uploadHeaders" rows="3" spellcheck="false" placeholder='{"Authorization":"Bearer ..."}'></textarea></label>
            <label v-if="uploadHttpMethod !== 'GET'"><span>请求体</span><textarea v-model="uploadRequestBody" rows="4" spellcheck="false" placeholder='{"documentId":"report-001"}'></textarea></label>
            <label class="document-private-network"><input v-model="uploadAllowPrivateNetwork" type="checkbox"><span>允许访问内网地址</span></label>
            <small>仅在可信内网接口需要时开启；本机、回环及链路本地地址始终禁止访问。</small>
          </section>
        </div>

        <input v-if="uploadMode === 'url' || (uploadForm.files?.length || 0) <= 1" v-model="uploadForm.title" placeholder="文档标题（可选，默认使用文件名）">
        <input v-model="uploadForm.source" placeholder="文档来源">
        <section class="upload-category-field">
          <div class="upload-category-mode" aria-label="分类方式">
            <button
              type="button"
              :class="{ active: uploadForm.categoryMode === 'existing' }"
              :disabled="uploading || uploadCategoryOptions.length === 0"
              @click="uploadForm.categoryMode = 'existing'"
            >
              已有分类
            </button>
            <button
              type="button"
              :class="{ active: uploadForm.categoryMode === 'custom' }"
              :disabled="uploading"
              @click="uploadForm.categoryMode = 'custom'"
            >
              新建分类
            </button>
          </div>
          <select
            v-if="uploadForm.categoryMode === 'existing'"
            v-model="uploadForm.category"
            required
            :disabled="uploading || uploadCategoriesLoading"
          >
            <option value="" disabled>{{ uploadCategoriesLoading ? "正在加载分类" : "选择已有分类" }}</option>
            <option
              v-for="category in uploadCategoryOptions"
              :key="category.name"
              :value="category.name"
            >
              {{ category.name }}
            </option>
          </select>
          <input
            v-else
            v-model.trim="uploadForm.newCategory"
            required
            placeholder="输入新分类名称"
          >
        </section>
        <input v-model="uploadForm.date" type="date">
        <select v-model="uploadForm.documentType" class="document-type-select">
          <option v-for="option in documentTypeOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
        <input v-model="uploadForm.tags" placeholder="标签，多个用逗号分隔">

        <p v-if="uploadError" class="search-error">{{ uploadError }}</p>
        <p v-if="uploadNotice" class="search-upload-notice">{{ uploadNotice }}</p>

        <footer>
          <button v-if="uploading" type="button" class="secondary-button" @click="terminateDocumentUpload">终止上传</button>
          <button type="submit" class="primary-button" :disabled="uploading">
            {{ uploading ? (uploadMode === 'url' ? "同步中" : "上传中") : (uploadMode === 'url' ? "同步文档" : "上传文档") }}
          </button>
        </footer>
      </form>
    </div>

    <div v-if="viewerOpen" class="search-viewer-backdrop">
      <section class="search-viewer">
        <header>
          <div>
            <p>{{ viewerDocument?.source || viewerResult?.source }} · {{ viewerDocument?.date || viewerResult?.date }}</p>
            <h2>{{ viewerDocument?.title || viewerResult?.title || "文档内容" }}</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeViewer">×</button>
        </header>

        <p v-if="viewerLoading" class="search-empty">正在加载文档内容...</p>
        <p v-else-if="viewerError" class="search-error">{{ viewerError }}</p>
        <div v-else class="search-viewer-body">
          <section v-if="viewerResult?.matchedChunks?.length" class="matched-chunks">
            <h3>匹配内容</h3>
            <article v-for="chunk in viewerResult.matchedChunks" :key="chunk.chunkId || chunk.chunkIndex">
              <span>片段 {{ chunk.chunkIndex + 1 }}</span>
              <p>{{ chunk.text }}</p>
            </article>
          </section>

          <section class="document-content">
            <h3>完整正文</h3>
            <pre>{{ viewerDocument?.content || "暂无正文内容" }}</pre>
          </section>
        </div>
      </section>
    </div>
  </section>
</template>

<script src="../js/views/AiSearchView.js"></script>
