<template>
  <section class="feature-view library-view">
    <header>
      <p>
        <a href="#" class="library-title-link" @click.prevent="backToSearch">返回文档检索</a>
      </p>
    </header>

    <section class="library-toolbar">
      <label class="library-search-field">
        <span>文档检索</span>
        <input
          v-model="titleKeyword"
          placeholder="按文档标题检索"
          @keyup.enter="searchByTitle"
        >
      </label>
      <button type="button" @click="searchByTitle">检索</button>
    </section>

    <p v-if="message" :class="titleExists ? 'library-success' : 'library-empty'">{{ message }}</p>
    <p v-if="error" class="library-error">{{ error }}</p>

    <div class="library-layout">
      <aside class="library-categories">
        <div class="category-heading">
          <span>分类</span>
          <button
            type="button"
            class="category-add-button"
            title="创建分类"
            aria-label="创建分类"
            @click="openCategoryDialog"
          >
            +
          </button>
        </div>

        <button
          v-for="category in categories"
          :key="category.name"
          type="button"
          :class="{ active: activeCategory === category.name }"
          @click="selectCategory(category.name)"
        >
          <span>{{ categoryLabel(category.name) }}</span>
          <strong>{{ category.count }}</strong>
        </button>
      </aside>

      <section class="library-documents">
        <div class="library-summary">
          <span>{{ loading ? "加载中" : `共 ${documentCount} 份文档，匹配 ${total} 份，当前显示 ${pageStart}-${pageEnd} 份` }}</span>
        </div>

        <article v-for="item in pagedDocuments" :key="item.docId" class="library-document">
          <div>
            <strong>{{ item.title }}</strong>
            <p>{{ item.summary }}</p>
            <span>{{ item.source }} · {{ item.date }} · {{ categoryLabel(item.category) }} · v{{ item.version || 1 }}</span>
          </div>
          <div class="library-document-actions">
            <button type="button" @click="openDocument(item.docId)">查看</button>
            <button type="button" class="danger-action" @click="removeDocument(item)">删除</button>
          </div>
        </article>

        <p v-if="!loading && documents.length === 0" class="library-empty">
          当前没有可展示文档。
        </p>

        <nav v-if="total > pageSize" class="library-pagination" aria-label="文档分页">
          <span>第 {{ page }} / {{ pageCount }} 页，每页 {{ pageSize }} 份</span>
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
      </section>
    </div>

    <div v-if="categoryDialogOpen" class="category-dialog-backdrop" @click.self="closeCategoryDialog">
      <section class="category-dialog">
        <header>
          <div>
            <p>文档分类</p>
            <h2>创建分类</h2>
          </div>
          <button type="button" class="viewer-close" @click="closeCategoryDialog">×</button>
        </header>

        <label>
          <span>分类名称</span>
          <input
            ref="categoryNameInput"
            v-model="newCategoryName"
            placeholder="例如：半导体、宏观策略"
            @keyup.enter="createCategory"
          >
        </label>

        <footer>
          <button type="button" class="secondary-button" :disabled="creatingCategory" @click="closeCategoryDialog">取消</button>
          <button type="button" :disabled="creatingCategory" @click="createCategory">
            {{ creatingCategory ? "创建中" : "创建" }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="viewerOpen" class="document-viewer-backdrop" @click.self="closeDocument">
      <section class="document-viewer">
        <header>
          <div>
            <p>{{ documentTypeLabel(viewerType) }} <span v-if="viewerVersionSummary">· {{ viewerVersionSummary }}</span></p>
            <h2>{{ viewerDocument?.title || "文档详情" }}</h2>
          </div>
          <button type="button" class="viewer-close" @click="closeDocument">×</button>
        </header>

        <p v-if="viewerLoading" class="library-empty">正在加载文档...</p>
        <p v-else-if="viewerError" class="library-error">{{ viewerError }}</p>

        <div v-else-if="viewerDocument" class="viewer-body">
          <div class="viewer-meta">
            <span>{{ viewerDocument.source }}</span>
            <span>{{ viewerDocument.date }}</span>
            <span>{{ viewerDocument.fileName || "提取正文" }}</span>
            <span>v{{ viewerDocument.version || 1 }}</span>
          </div>

          <div v-if="hasMultipleVersions" class="viewer-versions" aria-label="文档版本">
            <button
              v-for="version in viewerVersions"
              :key="version.docId"
              type="button"
              :class="{ active: version.version === selectedVersion }"
              :title="formatVersionTime(version.updatedAt || version.uploadedAt)"
              @click="switchDocumentVersion(version.version)"
            >
              <span>v{{ version.version }}</span>
              <strong v-if="version.latestVersion">最新</strong>
            </button>
          </div>

          <div v-if="viewerMode === 'pdf'" ref="pdfViewer" class="viewer-pdf"></div>
          <div v-else-if="viewerMode === 'word'" ref="wordViewer" class="viewer-word"></div>
          <div v-else-if="viewerMode === 'html'" class="viewer-html" v-html="viewerHtml"></div>
          <pre v-else class="viewer-text">{{ viewerDocument.content }}</pre>
        </div>
      </section>
    </div>
  </section>
</template>

<script src="../js/views/LibraryView.js"></script>
