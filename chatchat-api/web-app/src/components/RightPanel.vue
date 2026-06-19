<template>
  <div class="right-panel" :class="{ collapsed }">
    <section class="right-panel-shell">
      <div class="right-panel-head">
        <div>
          <strong>业务侧栏</strong>
          <span>个人工作台快捷入口</span>
        </div>
        <button
          type="button"
          :aria-label="collapsed ? '展开侧边工具栏' : '收起侧边工具栏'"
          :title="collapsed ? '展开侧边工具栏' : '收起侧边工具栏'"
          @click="$emit('toggle-collapsed')"
        >
          <PanelRightOpen v-if="collapsed" :size="18" stroke-width="1.8" />
          <PanelRightClose v-else :size="18" stroke-width="1.8" />
        </button>
      </div>

      <div v-if="collapsed" class="right-tool-rail">
        <button
          v-for="item in railItems"
          :key="item.id"
          type="button"
          :title="item.label"
          :aria-label="item.label"
          @click="$emit('toggle-collapsed')"
        >
          <component :is="item.icon" :size="18" stroke-width="2" />
          <span :class="{ urgent: item.urgent }">{{ item.count }}</span>
        </button>
      </div>

      <template v-else>
        <p v-if="error" class="right-panel-error">{{ error }}</p>
        <section class="right-module todo-module">
          <header>
            <span class="right-module-title">
              <ClipboardList :size="16" stroke-width="2" />
              <strong>待办任务</strong>
              <span v-if="todoItems.length" class="module-count">{{ todoItems.length }}</span>
            </span>
            <button
              type="button"
              class="todo-refresh"
              :disabled="todoLoading"
              aria-label="刷新待办"
              title="刷新待办"
              @click="$emit('refresh-todos')"
            >
              <RefreshCw :class="{ spinning: todoLoading }" :size="14" stroke-width="2" />
            </button>
          </header>
          <div class="right-module-body">
            <p v-if="todoError" class="todo-error">{{ todoError }}</p>
            <p v-else-if="!todoLoading && todoItems.length === 0" class="todo-empty">暂无任务</p>
            <button
              v-for="todo in visibleTodos"
              :key="todo.id"
              class="todo-item"
              type="button"
              @click="openTodo(todo)"
            >
              <span class="todo-type" :class="todoTypeClass(todo.todoType)">{{ todoTypeLabel(todo.todoType) }}</span>
              <strong>{{ todo.title }}</strong>
              <small>{{ todo.source || todo.agentId || "Agent Runtime" }}</small>
              <time>{{ todoTime(todo) }}</time>
            </button>
            <button
              v-if="todoItems.length > visibleTodos.length"
              type="button"
              class="todo-detail-link todo-more-link"
              @click="$emit('navigate', 'tasks')"
            >
              查看全部 {{ todoItems.length }} 条
            </button>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <FileText :size="16" stroke-width="2" />
              <strong>最近文档</strong>
            </span>
            <button type="button" @click="$emit('navigate', 'library')">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="document in recentDocuments" :key="`${document.targetId}-${document.createdAt}`" class="report-item shortcut-item">
              <span class="file-badge" :class="docBadgeClass(document)">{{ docMark(document) }}</span>
              <div>
                <strong>{{ document.title || document.targetId }}</strong>
                <time>{{ shortcutTime(document.createdAt) }}</time>
                <div class="shortcut-actions">
                  <button
                    type="button"
                    :disabled="!canPreviewDocument(document)"
                    :title="documentPreviewTitle(document)"
                    @click="openDocument(document)"
                  >
                    查看
                  </button>
                  <button type="button" @click="askAiAboutDocument(document)">问AI</button>
                </div>
              </div>
            </article>
            <p v-if="!loading && recentDocuments.length === 0" class="right-panel-empty">暂无最近文档</p>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <Star :size="16" stroke-width="2" />
              <strong>收藏夹</strong>
            </span>
            <button type="button" @click="$emit('navigate', 'favorites')">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="favorite in favorites" :key="favorite.id || favorite.targetId" class="simple-row shortcut-item">
              <span class="favorite-star"></span>
              <div>
                <strong>{{ favorite.title || favorite.targetId }}</strong>
                <time>{{ shortcutTime(favorite.createdAt) }}</time>
                <div class="shortcut-actions">
                  <button
                    type="button"
                    :disabled="isUnsupportedDocumentFavorite(favorite)"
                    :title="documentPreviewTitle(favorite)"
                    @click="openFavorite(favorite)"
                  >
                    打开
                  </button>
                  <button type="button" @click="deleteFavorite(favorite)">取消收藏</button>
                </div>
              </div>
            </article>
            <p v-if="!loading && favorites.length === 0" class="right-panel-empty">暂无收藏内容</p>
          </div>
        </section>

        <section class="right-module">
          <header>
            <span class="right-module-title">
              <Bot :size="16" stroke-width="2" />
              <strong>最近使用Agent</strong>
            </span>
            <button type="button" @click="$emit('navigate', 'agents')">全部</button>
          </header>
          <div class="right-module-body">
            <article v-for="agent in recentAgents" :key="agent.targetId" class="agent-row shortcut-item">
              <span>{{ agentShortName(agent) }}</span>
              <div>
                <strong>{{ agent.title || agent.targetId }}</strong>
                <time>{{ shortcutTime(agent.createdAt) }}</time>
                <div class="shortcut-actions">
                  <button type="button" @click="continueAgent(agent, false)">继续对话</button>
                  <button type="button" @click="continueAgent(agent, true)">新建会话</button>
                </div>
              </div>
            </article>
            <p v-if="!loading && recentAgents.length === 0" class="right-panel-empty">暂无最近使用Agent</p>
          </div>
        </section>
      </template>
    </section>

    <div v-if="selectedTodo" class="todo-detail-backdrop" @click.self="closeTodo">
      <section class="todo-detail-panel" role="dialog" aria-modal="true" :aria-label="selectedTodo.title">
        <header>
          <div>
            <span class="todo-type" :class="todoTypeClass(selectedTodo.todoType)">
              {{ todoTypeLabel(selectedTodo.todoType) }}
            </span>
            <h2>{{ selectedTodo.title }}</h2>
          </div>
          <button type="button" aria-label="关闭" title="关闭" @click="closeTodo">
            <XCircle :size="18" stroke-width="2" />
          </button>
        </header>
        <dl>
          <div>
            <dt>来源Agent</dt>
            <dd>{{ selectedTodo.source || selectedTodo.agentId || "Agent Runtime" }}</dd>
          </div>
          <div>
            <dt>触发原因</dt>
            <dd>{{ selectedTodoReason }}</dd>
          </div>
        </dl>
        <p v-if="selectedTodoCountdown" class="todo-confirm-timeout">
          该操作需要确认，3分钟内未确认将自动取消任务。剩余 {{ selectedTodoCountdown }}
        </p>
        <div class="todo-confirm-content-wrap">
          <p class="todo-confirm-content" :class="{ collapsed: selectedTodoContentCanToggle && !todoContentExpanded }">
            {{ selectedTodoContent }}
          </p>
          <button
            v-if="selectedTodoContentCanToggle"
            type="button"
            class="todo-content-toggle"
            :aria-expanded="todoContentExpanded"
            @click="todoContentExpanded = !todoContentExpanded"
          >
            <ChevronUp v-if="todoContentExpanded" :size="14" stroke-width="2" />
            <ChevronDown v-else :size="14" stroke-width="2" />
            <span>{{ todoContentExpanded ? "收起消息" : "展开消息" }}</span>
          </button>
        </div>
        <div class="todo-detail-actions">
          <template v-if="selectedTodo.todoType === 'TOOL_CONFIRMATION'">
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('approve')">
              <CheckCircle2 :size="15" stroke-width="2" />
              <span>同意执行</span>
            </button>
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('reject')">
              <XCircle :size="15" stroke-width="2" />
              <span>拒绝</span>
            </button>
          </template>
          <template v-else-if="selectedTodo.todoType === 'FAILURE_RETRY'">
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('retry')">
              <RotateCcw :size="15" stroke-width="2" />
              <span>重试</span>
            </button>
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('terminate')">
              <XCircle :size="15" stroke-width="2" />
              <span>终止</span>
            </button>
          </template>
          <template v-else>
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('useful')">
              <CheckCircle2 :size="15" stroke-width="2" />
              <span>有用</span>
            </button>
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('adopted')">
              <CheckCircle2 :size="15" stroke-width="2" />
              <span>采纳</span>
            </button>
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('resolved')">
              <CheckCircle2 :size="15" stroke-width="2" />
              <span>解决</span>
            </button>
            <button type="button" :disabled="isTodoActionLoading(selectedTodo)" @click="emitTodoAction('unresolved')">
              <XCircle :size="15" stroke-width="2" />
              <span>未解决</span>
            </button>
          </template>
          <button type="button" class="todo-detail-link" @click="openTodoDetail">查看详情</button>
        </div>
      </section>
    </div>
  </div>
</template>

<script src="../js/components/RightPanel.js"></script>
