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
        <p v-if="categoryReindexRunning" class="category-reindex-status">
          <RefreshCw :size="13" class="spin-icon" />
          <span>索引重建任务运行中，请等待完成</span>
        </p>
        <div
          v-if="openCategoryActionName || openDocumentActionId"
          class="category-action-backdrop"
          @click="closeCategoryActions(); closeDocumentActions()"
        ></div>

        <div
          v-for="category in categories"
          :key="category.name"
          class="category-row"
          :class="{ active: activeCategory === category.name }"
        >
          <button type="button" class="category-select-button" @click="selectCategory(category.name)">
            <span>{{ categoryLabel(category.name) }}</span>
            <strong>{{ category.count }}</strong>
          </button>
          <div class="category-row-actions">
            <button
              type="button"
              class="category-action-trigger"
              :disabled="categorySavingNames[category.name]"
              title="分类操作"
              aria-label="分类操作"
              :aria-expanded="openCategoryActionName === category.name"
              @click.stop="toggleCategoryActions(category.name)"
            >
              <MoreHorizontal :size="16" />
            </button>
            <div
              v-if="openCategoryActionName === category.name"
              class="category-action-menu"
              @click.stop
            >
              <button
                type="button"
                :disabled="categoryReindexRunning || category.count <= 0"
                :title="categoryReindexButtonTitle(category)"
                @click="openCategoryReindexDialog(category)"
              >
                <RefreshCw :size="14" :class="{ 'spin-icon': categoryReindexRunning }" />
                <span>{{ categoryReindexButtonLabel(category) }}</span>
              </button>
              <button v-if="isMutableCategory(category.name)" type="button" @click="renameCategory(category)">
                <Pencil :size="14" />
                <span>修改分类</span>
              </button>
              <button v-if="isMutableCategory(category.name)" type="button" class="danger-action" @click="deleteCategory(category)">
                <Trash2 :size="14" />
                <span>删除分类</span>
              </button>
            </div>
          </div>
        </div>
      </aside>

      <section class="library-documents">
        <div class="library-summary">
          <label v-if="canDeleteDocuments && pagedDocuments.length" class="library-select-all">
            <input
              type="checkbox"
              :checked="allVisibleDocumentsSelected"
              @change="toggleAllVisibleDocuments($event.target.checked)"
            >
            <span>{{ selectedDocumentCount ? `已选 ${selectedDocumentCount} 个` : "选择本页" }}</span>
          </label>
          <span>{{ loading ? "加载中" : `共 ${documentCount} 份文档，匹配 ${total} 份，当前显示 ${pageStart}-${pageEnd} 份` }}</span>
          <button
            v-if="canDeleteDocuments && pagedDocuments.length"
            type="button"
            class="library-batch-delete-button"
            :disabled="!selectedDocumentCount || loading"
            @click="openDocumentBatchDeleteDialog"
          >
            <Trash2 :size="14" />
            <span>批量删除</span>
          </button>
        </div>

        <article
          v-for="item in pagedDocuments"
          :key="item.docId"
          class="library-document"
          :class="{ selected: selectedDocumentIdSet.has(item.docId), 'no-delete': !canDeleteDocuments }"
        >
          <label v-if="canDeleteDocuments" class="library-document-select" @click.stop>
            <input
              type="checkbox"
              :checked="selectedDocumentIdSet.has(item.docId)"
              :aria-label="`选择文档 ${item.title || item.docId}`"
              @change="toggleDocumentSelection(item.docId, $event.target.checked)"
            >
          </label>
          <div>
            <strong>{{ item.title }}</strong>
            <p>{{ item.summary }}</p>
            <span>{{ item.source }} · {{ item.date }} · {{ categoryLabel(item.category) }} · v{{ item.version || 1 }}</span>
          </div>
          <div class="library-document-actions">
            <button
              type="button"
              :disabled="!canPreviewDocument(item)"
              :title="documentPreviewTitle(item)"
              @click="openDocument(item.docId, item)"
            >
              查看
            </button>
            <button type="button" :disabled="favoriteSavingIds[item.docId]" @click="favoriteDocument(item)">
              {{ favoriteSavingIds[item.docId] ? "收藏中" : "收藏" }}
            </button>
            <div class="document-more-actions">
              <button
                type="button"
                class="document-more-trigger"
                title="更多操作"
                :aria-expanded="openDocumentActionId === item.docId"
                @click.stop="toggleDocumentActions(item.docId)"
              >
                更多
              </button>
              <div
                v-if="openDocumentActionId === item.docId"
                class="document-action-menu"
                @click.stop
              >
                <button
                  type="button"
                  :disabled="documentCategorySavingIds[item.docId] || editableCategories.length === 0"
                  :title="editableCategories.length ? '修改文档分类' : '请先创建分类'"
                  @click="openDocumentCategoryDialog(item)"
                >
                  <Pencil :size="14" />
                  <span>{{ documentCategorySavingIds[item.docId] ? "修改中" : "改分类" }}</span>
                </button>
                <button
                  type="button"
                  :disabled="documentReindexingIds[item.docId]"
                  title="重建文档索引"
                  @click="reindexDocument(item)"
                >
                  <RefreshCw :size="14" />
                  <span>{{ documentReindexingIds[item.docId] ? "重建中" : "重建索引" }}</span>
                </button>
                <button v-if="canDeleteDocuments" type="button" class="danger-action" @click="removeDocument(item)">
                  <Trash2 :size="14" />
                  <span>删除</span>
                </button>
              </div>
            </div>
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

    <div v-if="categoryDeleteDialogOpen" class="category-dialog-backdrop" @click.self="closeCategoryDeleteDialog">
      <section class="category-dialog delete-confirm-dialog">
        <header>
          <div>
            <p>分类操作</p>
            <h2>删除分类</h2>
          </div>
          <button type="button" class="viewer-close" :disabled="categoryDeleteSubmitting" @click="closeCategoryDeleteDialog">×</button>
        </header>

        <div class="reindex-confirm-body delete-confirm-body">
          <div class="reindex-confirm-icon delete-confirm-icon">
            <Trash2 :size="22" />
          </div>
          <div>
            <p class="reindex-confirm-title">
              确认删除分类「{{ categoryLabel(categoryDeleteItem?.name) }}」？
            </p>
            <p class="reindex-confirm-text">
              删除分类后，{{ categoryDeleteItem?.count || 0 }} 份关联文档会移除该分类标记，文档本身不会被删除。
            </p>
          </div>
        </div>

        <div class="delete-confirm-note">
          <span>危险操作</span>
          <strong>删除后分类入口不可恢复，需要时只能重新创建分类。</strong>
        </div>

        <footer>
          <button type="button" class="secondary-button" :disabled="categoryDeleteSubmitting" @click="closeCategoryDeleteDialog">取消</button>
          <button type="button" class="danger-confirm-button" :disabled="categoryDeleteSubmitting" @click="submitCategoryDelete">
            {{ categoryDeleteSubmitting ? "删除中" : "确认删除" }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="documentBatchDeleteDialogOpen" class="category-dialog-backdrop" @click.self="closeDocumentBatchDeleteDialog">
      <section class="category-dialog delete-confirm-dialog">
        <header>
          <div>
            <p>文档操作</p>
            <h2>批量删除文档</h2>
          </div>
          <button type="button" class="viewer-close" :disabled="documentBatchDeleteSubmitting" @click="closeDocumentBatchDeleteDialog">x</button>
        </header>

        <div class="reindex-confirm-body delete-confirm-body">
          <div class="reindex-confirm-icon delete-confirm-icon">
            <Trash2 :size="22" />
          </div>
          <div>
            <p class="reindex-confirm-title">
              确认删除已选 {{ selectedDocumentCount }} 个文档？
            </p>
            <p class="reindex-confirm-text">
              删除后这些文档会从知识库文档列表和检索索引中移除，此操作不可恢复。
            </p>
          </div>
        </div>

        <div class="delete-confirm-note">
          <span>危险操作</span>
          <strong>请确认这些文档不再需要被检索或引用后再删除。</strong>
        </div>

        <footer>
          <button type="button" class="secondary-button" :disabled="documentBatchDeleteSubmitting" @click="closeDocumentBatchDeleteDialog">取消</button>
          <button type="button" class="danger-confirm-button" :disabled="documentBatchDeleteSubmitting || !selectedDocumentCount" @click="submitDocumentBatchDelete">
            {{ documentBatchDeleteSubmitting ? "删除中" : "确认删除" }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="documentDeleteDialogOpen" class="category-dialog-backdrop" @click.self="closeDocumentDeleteDialog">
      <section class="category-dialog delete-confirm-dialog">
        <header>
          <div>
            <p>文档操作</p>
            <h2>删除文档</h2>
          </div>
          <button type="button" class="viewer-close" :disabled="documentDeleteSubmitting" @click="closeDocumentDeleteDialog">×</button>
        </header>

        <div class="reindex-confirm-body delete-confirm-body">
          <div class="reindex-confirm-icon delete-confirm-icon">
            <Trash2 :size="22" />
          </div>
          <div>
            <p class="reindex-confirm-title">
              确认删除文档「{{ documentDeleteItem?.title || documentDeleteItem?.docId || "未命名文档" }}」？
            </p>
            <p class="reindex-confirm-text">
              删除后该文档将从知识库文档列表和检索索引中移除，此操作不可恢复。
            </p>
          </div>
        </div>

        <div class="delete-confirm-note">
          <span>危险操作</span>
          <strong>请确认该文档不再需要被检索或引用后再删除。</strong>
        </div>

        <footer>
          <button type="button" class="secondary-button" :disabled="documentDeleteSubmitting" @click="closeDocumentDeleteDialog">取消</button>
          <button type="button" class="danger-confirm-button" :disabled="documentDeleteSubmitting" @click="submitDocumentDelete">
            {{ documentDeleteSubmitting ? "删除中" : "确认删除" }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="categoryReindexDialogOpen" class="category-dialog-backdrop" @click.self="closeCategoryReindexDialog">
      <section class="category-dialog reindex-confirm-dialog">
        <header>
          <div>
            <p>索引维护</p>
            <h2>重建分类索引</h2>
          </div>
          <button type="button" class="viewer-close" :disabled="categoryReindexSubmitting" @click="closeCategoryReindexDialog">×</button>
        </header>

        <div class="reindex-confirm-body">
          <div class="reindex-confirm-icon">
            <RefreshCw :size="22" />
          </div>
          <div>
            <p class="reindex-confirm-title">
              确认重建分类「{{ categoryLabel(categoryReindexItem?.name) }}」的索引？
            </p>
            <p class="reindex-confirm-text">
              本次会在后台逐个重建该分类下 {{ categoryReindexItem?.count || 0 }} 份文档的检索索引。任务运行期间，其他分类重建会被暂时禁用。
            </p>
          </div>
        </div>

        <div class="reindex-confirm-note">
          <span>后台任务</span>
          <strong>提交后可以继续浏览文档，页面会自动刷新任务状态。</strong>
        </div>

        <footer>
          <button type="button" class="secondary-button" :disabled="categoryReindexSubmitting" @click="closeCategoryReindexDialog">取消</button>
          <button type="button" :disabled="categoryReindexSubmitting || categoryReindexRunning" @click="submitCategoryReindex">
            {{ categoryReindexSubmitting ? "提交中" : "确认重建" }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="documentCategoryDialogOpen" class="category-dialog-backdrop" @click.self="closeDocumentCategoryDialog">
      <section class="category-dialog document-category-dialog">
        <header>
          <div>
            <p>文档分类</p>
            <h2>修改文档分类</h2>
          </div>
          <button type="button" class="viewer-close" @click="closeDocumentCategoryDialog">×</button>
        </header>

        <div class="document-category-current">
          <span>当前文档</span>
          <strong>{{ documentCategoryItem?.title || "未命名文档" }}</strong>
        </div>

        <label>
          <span>选择分类</span>
          <select
            ref="documentCategorySelect"
            v-model="selectedDocumentCategory"
            :disabled="documentCategorySavingIds[documentCategoryItem?.docId]"
          >
            <option
              v-for="category in editableCategories"
              :key="category.name"
              :value="category.name"
            >
              {{ categoryLabel(category.name) }}
            </option>
          </select>
        </label>

        <footer>
          <button
            type="button"
            class="secondary-button"
            :disabled="documentCategorySavingIds[documentCategoryItem?.docId]"
            @click="closeDocumentCategoryDialog"
          >
            取消
          </button>
          <button
            type="button"
            :disabled="documentCategorySavingIds[documentCategoryItem?.docId] || !selectedDocumentCategory"
            @click="submitDocumentCategory"
          >
            {{ documentCategorySavingIds[documentCategoryItem?.docId] ? "保存中" : "保存" }}
          </button>
        </footer>
      </section>
    </div>

    <div v-if="categoryDialogOpen" class="category-dialog-backdrop" @click.self="closeCategoryDialog">
      <section class="category-dialog">
        <header>
          <div>
            <p>文档分类</p>
            <h2>{{ categoryDialogTitle }}</h2>
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
            {{ categorySubmitLabel }}
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

          <div v-if="viewerMode === 'html'" class="viewer-html" v-html="viewerHtml"></div>
          <pre v-else class="viewer-text">{{ viewerDocument.content }}</pre>
        </div>
      </section>
    </div>
  </section>
</template>

<script src="../js/views/LibraryView.js"></script>
