<template>
  <aside class="assistant-sidebar">
    <div class="brand-block">
      <img class="brand-logo" :src="agentRuntimeLogo" alt="灵动智策 LingDong Insight">
      <button
        class="sidebar-toggle"
        type="button"
        :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
        :title="collapsed ? '展开侧栏' : '收起侧栏'"
        @click="$emit('toggle-sidebar')"
      >
        <PanelLeftOpen v-if="collapsed" :size="18" stroke-width="1.8" />
        <PanelLeftClose v-else :size="18" stroke-width="1.8" />
      </button>
    </div>

    <div class="sidebar-scroll">
      <nav class="sidebar-nav" aria-label="主导航">
        <section v-for="group in navGroups" :key="group.id" class="nav-group">
          <button
            v-if="!collapsed"
            class="nav-group-trigger"
            type="button"
            :aria-expanded="!isGroupCollapsed(group)"
            @click="toggleGroup(group)"
          >
            <span>{{ group.label }}</span>
            <ChevronDown
              class="nav-group-caret"
              :class="{ collapsed: isGroupCollapsed(group) }"
              :size="15"
              stroke-width="2"
            />
          </button>
          <div v-show="!isGroupCollapsed(group)" class="nav-group-list">
            <template v-for="item in group.items" :key="item.id">
              <div v-if="item.children?.length" class="nav-submenu" :class="{ active: isNavItemActive(item) }">
                <button
                  class="nav-submenu-trigger"
                  type="button"
                  :title="collapsed ? item.label : ''"
                  :aria-expanded="!isNavItemCollapsed(item)"
                  @click="activateNavItem(item)"
                >
                  <component :is="iconComponent(item.icon)" class="nav-symbol" :size="18" stroke-width="2" />
                  <span>{{ item.label }}</span>
                  <ChevronDown
                    v-if="!collapsed"
                    class="nav-submenu-caret"
                    :class="{ collapsed: isNavItemCollapsed(item) }"
                    :size="14"
                    stroke-width="2"
                  />
                </button>
                <div v-show="!isNavItemCollapsed(item)" class="nav-submenu-list">
                  <button
                    v-for="child in item.children"
                    :key="child.id"
                    :class="{ active: activeView === child.id }"
                    type="button"
                    @click="$emit('navigate', child.id)"
                  >
                    <component :is="iconComponent(child.icon)" class="nav-symbol" :size="16" stroke-width="2" />
                    <span>{{ child.label }}</span>
                  </button>
                </div>
              </div>
              <button
                v-else
                :class="{ active: activeView === item.id }"
                type="button"
                :title="collapsed ? item.label : ''"
                @click="$emit('navigate', item.id)"
              >
                <component :is="iconComponent(item.icon)" class="nav-symbol" :size="18" stroke-width="2" />
                <span>{{ item.label }}</span>
              </button>
            </template>
          </div>
        </section>
      </nav>

      <section class="recent-block">
        <div class="recent-head">
          <span>最近对话</span>
          <span>{{ historyLoading ? "加载中" : `${filteredConversations.length}/${recentConversations.length}${historyHasMore ? '+' : ''}` }}</span>
        </div>
        <label class="history-search">
          <Search :size="15" stroke-width="2" />
          <input v-model="historyKeyword" type="search" placeholder="搜索历史会话" />
        </label>
        <p v-if="historyError" class="recent-error">{{ historyError }}</p>
        <p v-else-if="!historyLoading && filteredConversations.length === 0" class="recent-empty">暂无匹配的历史会话</p>
        <article
          v-for="conversation in visibleConversations"
          :key="conversationKey(conversation)"
          class="recent-card"
          :class="{
            active: isConversationActive(conversation),
            unfinished: isUnfinished(conversation),
            running: resolveStatus(conversation) === 'running',
            failed: resolveStatus(conversation) === 'failed',
            'menu-open': conversationMenuOpen(conversation)
          }"
          role="button"
          tabindex="0"
          @click="selectConversation(conversation)"
          @keydown.enter.prevent="selectConversation(conversation)"
        >
          <span class="recent-title">{{ conversationTitle(conversation) }}</span>
          <time
            class="recent-created-at"
            :datetime="conversationCreatedAtIso(conversation)"
            :title="conversationCreatedAtTitle(conversation)"
          >
            创建 {{ formatConversationCreatedAt(conversation) }}
          </time>
          <span v-if="statusLabel(conversation)" class="recent-status">
            <span v-if="resolveStatus(conversation) === 'running'" class="recent-spinner"></span>
            {{ statusLabel(conversation) }}
          </span>
          <span
            class="recent-favorite"
            :class="{ active: isConversationFavorited(conversation), saving: isFavoriteSaving(conversation) }"
            role="button"
            tabindex="0"
            :title="isConversationFavorited(conversation) ? '取消收藏' : '收藏会话'"
            :aria-label="isConversationFavorited(conversation) ? '取消收藏会话' : '收藏会话'"
            @click.stop="favoriteConversation(conversation)"
            @keydown.enter.stop.prevent="favoriteConversation(conversation)"
          >
            <Star :size="13" stroke-width="2" :fill="isConversationFavorited(conversation) ? 'currentColor' : 'none'" />
          </span>
          <span
            class="recent-more"
            :class="{ active: conversationMenuOpen(conversation) }"
            role="button"
            tabindex="0"
            title="会话操作"
            aria-label="打开会话操作菜单"
            :aria-expanded="conversationMenuOpen(conversation)"
            @click.stop="toggleConversationMenu(conversation)"
            @keydown.enter.stop.prevent="toggleConversationMenu(conversation)"
          >
            <MoreHorizontal :size="16" stroke-width="2" />
          </span>
          <div v-if="conversationMenuOpen(conversation)" class="recent-action-menu" role="menu" @click.stop>
            <button type="button" role="menuitem" @click="openRenameConversationDialog(conversation)">
              <Pencil :size="14" stroke-width="2" />
              重命名
            </button>
            <button
              type="button"
              role="menuitem"
              class="danger"
              :disabled="isConversationInProgress(conversation)"
              :title="isConversationInProgress(conversation) ? '进行中的会话不能删除' : '删除会话'"
              @click="openDeleteConversationDialog(conversation)"
            >
              <Trash2 :size="14" stroke-width="2" />
              删除
            </button>
          </div>
        </article>
        <button
          v-if="filteredConversations.length > 5"
          class="more-link"
          type="button"
          @click="showAllHistory = !showAllHistory"
        >
          {{ showAllHistory ? "收起历史" : "查看全部对话" }}
        </button>
        <button
          v-if="showAllHistory && historyHasMore"
          class="more-link"
          type="button"
          :disabled="historyLoading"
          @click="$emit('load-more-history')"
        >
          {{ historyLoading ? "加载中" : "加载更多历史" }}
        </button>
        <button class="more-link" type="button" @click="$emit('refresh-history')">刷新历史</button>
      </section>
    </div>

    <div class="user-card">
      <div class="user-avatar">{{ userAvatarLabel }}</div>
      <div class="user-copy">
        <strong>{{ displayUserId }}</strong>
        <span>用户ID</span>
      </div>
      <button
        type="button"
        aria-label="管理历史记录"
        title="管理历史记录"
        class="user-action history-manager-trigger"
        @click="openHistoryManager"
      >
        <MessageCircle :size="17" stroke-width="1.9" />
        <span class="history-count-badge">{{ historyCountLabel }}</span>
      </button>
      <button type="button" aria-label="退出登录" title="退出登录" class="user-more" @click="$emit('logout')">
        <LogOut :size="17" stroke-width="2" />
      </button>
    </div>

    <Teleport to="body">
      <div
        v-if="historyManagerOpen"
        class="history-manager-backdrop"
        role="presentation"
        @mousedown.self="closeHistoryManager"
      >
        <section
          class="history-manager-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="history-manager-title"
          @keydown.esc="closeHistoryManager"
        >
          <header class="history-manager-header">
            <div>
              <h2 id="history-manager-title">历史记录</h2>
              <p>共 {{ historyManagerTotal }} 条记录，每页 {{ historyManagerPageSize }} 条</p>
            </div>
            <button type="button" aria-label="关闭历史记录弹窗" title="关闭" @click="closeHistoryManager">
              <X :size="19" stroke-width="2" />
            </button>
          </header>

          <div class="history-manager-toolbar">
            <label class="history-manager-search">
              <Search :size="17" stroke-width="2" />
              <input
                ref="historyManagerSearch"
                v-model="managerKeyword"
                type="search"
                placeholder="搜索会话标题或会话 ID"
              />
            </label>
            <label class="history-manager-select-all">
              <input
                type="checkbox"
                :checked="allManagerConversationsSelected"
                :indeterminate.prop="someManagerConversationsSelected"
                :disabled="deletableManagerConversations.length === 0 || historyDeleting || historyManagerLoading"
                @change="toggleAllManagerConversations"
              />
              <span>全选本页</span>
            </label>
          </div>

          <div class="history-manager-list">
            <p v-if="historyManagerLoading" class="history-manager-state">正在加载历史记录…</p>
            <p v-else-if="managerPageConversations.length === 0" class="history-manager-state">
              {{ managerKeyword.trim() ? "没有匹配的历史记录" : "暂无历史记录" }}
            </p>
            <label
              v-for="conversation in managerPageConversations"
              v-else
              :key="`manager-${conversationKey(conversation)}`"
              class="history-manager-item"
              :class="{
                active: isConversationActive(conversation),
                locked: isConversationInProgress(conversation)
              }"
            >
              <input
                type="checkbox"
                :value="conversationKey(conversation)"
                v-model="selectedHistoryKeys"
                :disabled="historyDeleting || isConversationInProgress(conversation)"
                :title="isConversationInProgress(conversation) ? '进行中的会话不能删除' : ''"
              />
              <span class="history-manager-item-copy">
                <strong>{{ conversationTitle(conversation) }}</strong>
                <span>{{ formatConversationCreatedAt(conversation) }}</span>
              </span>
              <span v-if="statusLabel(conversation)" class="history-manager-item-status">
                {{ statusLabel(conversation) }}
              </span>
            </label>
          </div>

          <AppPagination
            :page="historyManagerPage"
            :page-size="historyManagerPageSize"
            :total="historyManagerTotal"
            :page-count="historyManagerPageCount"
            :disabled="historyDeleting || historyManagerLoading"
            aria-label="历史记录分页"
            @change="changeManagerPage"
          />

          <footer class="history-manager-footer">
            <span>已选择 {{ selectedManagerConversations.length }} 条</span>
            <div>
              <button type="button" class="history-manager-cancel" :disabled="historyDeleting" @click="closeHistoryManager">取消</button>
              <button
                type="button"
                class="history-manager-delete"
                :disabled="selectedManagerConversations.length === 0 || historyDeleting"
                @click="deleteSelectedHistory"
              >
                <Trash2 :size="15" stroke-width="2" />
                {{ historyDeleting ? "删除中…" : `删除所选（${selectedManagerConversations.length}）` }}
              </button>
            </div>
          </footer>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="deleteConfirmOpen"
        class="conversation-delete-backdrop"
        role="presentation"
        @mousedown.self="closeSelectedHistoryDeleteDialog"
      >
        <section
          class="conversation-delete-dialog"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="selected-history-delete-title"
          aria-describedby="selected-history-delete-description"
          @keydown.esc="closeSelectedHistoryDeleteDialog"
        >
          <div class="conversation-delete-icon" aria-hidden="true">
            <Trash2 :size="22" stroke-width="2" />
          </div>
          <div class="conversation-delete-copy">
            <h2 id="selected-history-delete-title">批量删除会话</h2>
            <p id="selected-history-delete-description">
              确定删除已选择的 {{ selectedManagerConversations.length }} 条历史会话吗？
            </p>
            <small>删除后无法恢复，这些会话中的历史消息也将一并移除。</small>
          </div>
          <footer class="conversation-delete-actions">
            <button
              ref="deleteSelectedHistoryCancel"
              type="button"
              class="conversation-delete-cancel"
              :disabled="historyDeleting"
              @click="closeSelectedHistoryDeleteDialog"
            >取消</button>
            <button
              type="button"
              class="conversation-delete-confirm"
              :disabled="historyDeleting"
              @click="confirmDeleteSelectedHistory"
            >
              <Trash2 :size="15" stroke-width="2" />
              {{ historyDeleting ? "删除中…" : "确认删除" }}
            </button>
          </footer>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="deleteConversationCandidate"
        class="conversation-delete-backdrop"
        role="presentation"
        @mousedown.self="closeDeleteConversationDialog"
      >
        <section
          class="conversation-delete-dialog"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="conversation-delete-title"
          aria-describedby="conversation-delete-description"
          @keydown.esc="closeDeleteConversationDialog"
        >
          <div class="conversation-delete-icon" aria-hidden="true">
            <Trash2 :size="22" stroke-width="2" />
          </div>
          <div class="conversation-delete-copy">
            <h2 id="conversation-delete-title">删除会话</h2>
            <p id="conversation-delete-description">确定要删除下面这条历史会话吗？</p>
            <strong>{{ conversationTitle(deleteConversationCandidate) }}</strong>
            <small>删除后无法恢复，会话中的历史消息也将一并移除。</small>
          </div>
          <footer class="conversation-delete-actions">
            <button
              ref="deleteConversationCancel"
              type="button"
              class="conversation-delete-cancel"
              @click="closeDeleteConversationDialog"
            >
              取消
            </button>
            <button type="button" class="conversation-delete-confirm" @click="confirmDeleteConversation">
              <Trash2 :size="15" stroke-width="2" />
              确认删除
            </button>
          </footer>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="renameConversationCandidate"
        class="conversation-delete-backdrop"
        role="presentation"
        @mousedown.self="closeRenameConversationDialog"
      >
        <section
          class="conversation-delete-dialog conversation-rename-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="conversation-rename-title"
          @keydown.esc="closeRenameConversationDialog"
        >
          <div class="conversation-rename-icon" aria-hidden="true">
            <Pencil :size="20" stroke-width="2" />
          </div>
          <form class="conversation-delete-copy" @submit.prevent="confirmRenameConversation">
            <h2 id="conversation-rename-title">重命名会话</h2>
            <p>修改后的名称会同步显示在最近对话和历史记录中。</p>
            <label class="conversation-rename-field">
              <span>会话名称</span>
              <input
                ref="renameConversationInput"
                v-model="renameConversationTitle"
                type="text"
                maxlength="256"
                autocomplete="off"
                placeholder="请输入会话名称"
              />
            </label>
            <footer class="conversation-delete-actions">
              <button type="button" class="conversation-delete-cancel" @click="closeRenameConversationDialog">
                取消
              </button>
              <button
                type="submit"
                class="conversation-rename-confirm"
                :disabled="!renameConversationTitle.trim() || renameConversationTitle.trim() === conversationTitle(renameConversationCandidate)"
              >
                确认修改
              </button>
            </footer>
          </form>
        </section>
      </div>
    </Teleport>
  </aside>
</template>

<script src="../js/components/AssistantSidebar.js"></script>
