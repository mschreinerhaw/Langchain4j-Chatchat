<template>
  <aside class="assistant-sidebar">
    <div class="brand-block">
      <img class="brand-logo" :src="agentRuntimeLogo" alt="LiveRuntime">
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
          <span>{{ historyLoading ? "加载中" : `${filteredConversations.length}/${recentConversations.length}` }}</span>
        </div>
        <button class="new-conversation-button" type="button" title="新建对话" @click="$emit('new-conversation')">
          <Plus :size="15" stroke-width="2.2" />
          <span>新建对话</span>
        </button>
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
          <span v-if="statusLabel(conversation)" class="recent-status">
            <span v-if="resolveStatus(conversation) === 'running'" class="recent-spinner"></span>
            {{ statusLabel(conversation) }}
          </span>
          <span
            class="recent-favorite"
            :class="{ active: isConversationFavorited(conversation), saving: isFavoriteSaving(conversation) }"
            role="button"
            tabindex="0"
            :title="isConversationFavorited(conversation) ? '已收藏' : '收藏会话'"
            :aria-label="isConversationFavorited(conversation) ? '已收藏会话' : '收藏会话'"
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
        <button class="more-link" type="button" @click="$emit('refresh-history')">刷新历史</button>
      </section>
    </div>

    <div class="user-card">
      <div class="user-avatar">{{ userAvatarLabel }}</div>
      <div class="user-copy">
        <strong>{{ displayUserId }}</strong>
        <span>用户ID</span>
      </div>
      <button type="button" aria-label="消息" title="消息" class="user-action">
        <MessageCircle :size="17" stroke-width="1.9" />
      </button>
      <button type="button" aria-label="退出登录" title="退出登录" class="user-more" @click="$emit('logout')">
        <LogOut :size="17" stroke-width="2" />
      </button>
    </div>
  </aside>
</template>

<script src="../js/components/AssistantSidebar.js"></script>
