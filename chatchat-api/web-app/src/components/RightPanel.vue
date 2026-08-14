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
              <strong>我的待办</strong>
              <span v-if="activeTodoCount" class="module-count">{{ activeTodoCount }}</span>
            </span>
            <button
              type="button"
              class="todo-refresh"
              :disabled="todoLoading"
              aria-label="刷新个人待办"
              title="刷新个人待办"
              @click="loadTodos"
            >
              <RefreshCw :class="{ spinning: todoLoading }" :size="14" stroke-width="2" />
            </button>
          </header>
          <div class="right-module-body">
            <form class="todo-quick-add" @submit.prevent="createTodo">
              <Plus :size="16" stroke-width="2.2" />
              <input
                v-model="newTodoTitle"
                maxlength="300"
                placeholder="添加任务，按 Enter 保存"
                aria-label="添加待办任务"
              />
              <button type="submit" :disabled="todoSaving || !newTodoTitle.trim()">添加</button>
            </form>
            <p v-if="todoError" class="todo-error">{{ todoError }}</p>
            <p v-else-if="!todoLoading && activeTodoCount === 0" class="todo-empty">今天没有待办，添加一条便签吧</p>
            <article
              v-for="todo in visibleTodos"
              :key="todo.id"
              class="personal-todo-item"
              :class="{ important: todo.important }"
            >
              <button class="todo-check" type="button" :aria-label="`完成：${todo.title}`" @click="toggleTodo(todo)">
                <Circle :size="18" stroke-width="2" />
              </button>
              <button class="todo-content" type="button" @click="editTodo(todo)">
                <strong>{{ todo.title }}</strong>
                <small v-if="todo.notes">{{ todo.notes }}</small>
                <time v-if="todo.dueAt" :class="{ overdue: isOverdue(todo) }">{{ todoDueLabel(todo) }}</time>
              </button>
              <button class="todo-star" type="button" :class="{ active: todo.important }" aria-label="切换重要标记" @click="toggleImportant(todo)">
                <Star :size="16" stroke-width="2" />
              </button>
            </article>
            <button
              type="button"
              class="todo-detail-link todo-more-link"
              @click="openTodoManager"
            >
              查看全部{{ activeTodoCount ? ` ${activeTodoCount} 条` : "" }}
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

    <div v-if="todoManagerOpen" class="todo-detail-backdrop" @click.self="closeTodoManager">
      <section class="todo-detail-panel personal-todo-dialog" role="dialog" aria-modal="true" aria-label="我的待办">
        <header>
          <div>
            <span class="todo-dialog-kicker">个人便签</span>
            <h2>{{ editingTodo ? "编辑待办" : "我的待办" }}</h2>
          </div>
          <button type="button" class="app-dialog-close" aria-label="关闭" title="关闭" @click="closeTodoManager">
            <XCircle :size="18" stroke-width="2" />
          </button>
        </header>
        <form v-if="editingTodo" class="todo-editor" @submit.prevent="saveEditedTodo">
          <label>任务内容<input v-model="todoDraft.title" maxlength="300" required /></label>
          <label>备注<textarea v-model="todoDraft.notes" maxlength="2000" rows="4" placeholder="补充说明（可选）"></textarea></label>
          <label>截止日期<input v-model="todoDraft.dueAt" type="datetime-local" /></label>
          <label class="todo-important-field"><input v-model="todoDraft.important" type="checkbox" /> 标记为重要</label>
          <div class="todo-editor-actions">
            <button type="button" class="danger" @click="removeTodo(editingTodo)"><Trash2 :size="15" />删除</button>
            <span></span>
            <button type="button" @click="editingTodo = null">返回列表</button>
            <button type="submit" class="primary" :disabled="todoSaving">保存</button>
          </div>
        </form>
        <div v-else class="todo-manager-list">
          <div class="todo-manager-tabs">
            <button type="button" :class="{ active: !showCompleted }" @click="showCompleted = false">未完成</button>
            <button type="button" :class="{ active: showCompleted }" @click="showCompleted = true">已完成</button>
          </div>
          <article v-for="todo in managerTodos" :key="todo.id" class="personal-todo-item manager-row">
            <button class="todo-check" type="button" @click="toggleTodo(todo)">
              <CheckCircle2 v-if="todo.completed" :size="19" />
              <Circle v-else :size="19" />
            </button>
            <button class="todo-content" type="button" @click="editTodo(todo)">
              <strong :class="{ completed: todo.completed }">{{ todo.title }}</strong>
              <small v-if="todo.notes">{{ todo.notes }}</small>
            </button>
            <button class="todo-star" type="button" :class="{ active: todo.important }" @click="toggleImportant(todo)"><Star :size="17" /></button>
          </article>
          <p v-if="managerTodos.length === 0" class="todo-empty">{{ showCompleted ? "暂无已完成任务" : "暂无未完成任务" }}</p>
        </div>
      </section>
    </div>
  </div>
</template>

<script src="../js/components/RightPanel.js"></script>
