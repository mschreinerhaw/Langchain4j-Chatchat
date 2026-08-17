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
            <button
              v-for="item in group.items"
              :key="item.id"
              :class="{ active: activeView === item.id }"
              type="button"
              :title="collapsed ? item.label : ''"
              @click="$emit('navigate', item.id)"
            >
              <component :is="iconComponent(item.icon)" class="nav-symbol" :size="18" stroke-width="2" />
              <span>{{ item.label }}</span>
            </button>
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
        <button
          v-for="conversation in visibleConversations"
          :key="conversationKey(conversation)"
          :class="{
            active: isConversationActive(conversation),
            unfinished: isUnfinished(conversation),
            running: resolveStatus(conversation) === 'running',
            failed: resolveStatus(conversation) === 'failed'
          }"
          type="button"
          @click="selectConversation(conversation)"
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
            class="recent-delete"
            role="button"
            tabindex="0"
            title="删除历史会话"
            aria-label="删除历史会话"
            @click.stop="deleteConversation(conversation)"
            @keydown.enter.stop.prevent="deleteConversation(conversation)"
          >
            <Trash2 :size="13" stroke-width="2" />
          </span>
        </button>
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
              <p>当前已加载 {{ recentConversations.length }} 条记录</p>
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
                :disabled="managerFilteredConversations.length === 0 || historyDeleting"
                @change="toggleAllManagerConversations"
              />
              <span>全选本页</span>
            </label>
          </div>

          <div class="history-manager-list">
            <p v-if="historyLoading && recentConversations.length === 0" class="history-manager-state">正在加载历史记录…</p>
            <p v-else-if="managerFilteredConversations.length === 0" class="history-manager-state">
              {{ managerKeyword.trim() ? "没有匹配的历史记录" : "暂无历史记录" }}
            </p>
            <label
              v-for="conversation in managerPageConversations"
              v-else
              :key="`manager-${conversationKey(conversation)}`"
              class="history-manager-item"
              :class="{ active: isConversationActive(conversation) }"
            >
              <input
                type="checkbox"
                :value="conversationKey(conversation)"
                v-model="selectedHistoryKeys"
                :disabled="historyDeleting"
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

          <nav v-if="managerFilteredConversations.length > 0" class="history-manager-pagination" aria-label="历史记录分页">
            <span>
              {{ managerPageStart + 1 }}–{{ managerPageEnd }} / {{ managerFilteredConversations.length }} 条
            </span>
            <div>
              <button
                type="button"
                :disabled="managerCurrentPage <= 1 || historyDeleting"
                aria-label="上一页"
                @click="changeManagerPage(managerCurrentPage - 1)"
              >
                上一页
              </button>
              <strong>第 {{ managerCurrentPage }} / {{ managerPageCount }} 页</strong>
              <button
                type="button"
                :disabled="managerCurrentPage >= managerPageCount || historyDeleting"
                aria-label="下一页"
                @click="changeManagerPage(managerCurrentPage + 1)"
              >
                下一页
              </button>
            </div>
          </nav>

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
  </aside>
</template>

<script src="../js/components/AssistantSidebar.js"></script>
