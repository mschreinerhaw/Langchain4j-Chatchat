<template>
  <section class="workspace-selection" aria-label="工作空间" :aria-busy="loading">
    <p class="workspace-breadcrumb"
      >平台管理 <ChevronRight :size="13" aria-hidden="true" /> 工作空间</p
    >
    <header class="workspace-page-header">
      <div>
        <h1>工作空间</h1>
        <p class="workspace-page-description"
          >为不同的工作，找到合适的空间。你的应用与工具，从这里开始。</p
        >
      </div>
      <span class="workspace-available"><span></span>{{ workspaces.length }} 个可用空间</span>
    </header>

    <div class="workspace-directory">
      <div class="workspace-toolbar">
        <div class="workspace-search" role="search">
          <Search :size="18" aria-hidden="true" />
          <input
            v-model="searchQuery"
            type="search"
            aria-label="搜索工作空间"
            placeholder="搜索空间名称或说明"
            :disabled="loading"
          />
          <button
            v-if="searchQuery"
            type="button"
            class="workspace-clear"
            aria-label="清空搜索"
            @click="searchQuery = ''"
            ><X :size="15" aria-hidden="true"
          /></button>
        </div>
        <button
          type="button"
          class="workspace-button workspace-button-secondary workspace-refresh"
          :disabled="loading"
          @click="$emit('reload-workspaces')"
        >
          <RefreshCw :size="16" :class="{ 'workspace-spinning': loading }" aria-hidden="true" />刷新
        </button>
      </div>
      <div class="workspace-category-row">
        <span class="workspace-filter-label"
          ><SlidersHorizontal :size="15" aria-hidden="true" />分类</span
        >
        <div class="workspace-categories" role="group" aria-label="按分类查看工作空间">
          <button
            v-for="category in categories"
            :key="category.id"
            type="button"
            :class="{ 'is-selected': categoryFilter === category.id }"
            :aria-pressed="categoryFilter === category.id"
            :disabled="loading"
            @click="categoryFilter = category.id"
          >
            <component :is="category.icon" :size="15" aria-hidden="true" />
            {{ category.label }}<span class="workspace-category-count">{{ category.count }}</span>
          </button>
        </div>
      </div>
      <div class="workspace-results-heading">
        <h2
          >{{ activeCategoryLabel }}<span>{{ filteredWorkspaces.length }}</span></h2
        >
        <span v-if="loading" class="workspace-loading" role="status"
          ><LoaderCircle
            :size="14"
            class="workspace-spinning"
            aria-hidden="true"
          />正在更新工作空间…</span
        >
        <span v-else class="workspace-results-hint">{{
          hasFilters ? "已按筛选条件显示" : "选择空间开始工作，也可以设置默认登录空间"
        }}</span>
      </div>
      <p v-if="notice" class="workspace-notice" role="status"
        ><CircleCheck :size="16" aria-hidden="true" />{{ notice }}</p
      >

      <div v-if="pagedWorkspaces.length" class="workspace-cards">
        <article
          v-for="workspace in pagedWorkspaces"
          :key="workspace.workspaceId"
          class="workspace-card"
          :class="{ 'is-default': workspace.workspaceId === defaultWorkspaceId }"
        >
          <div class="workspace-card-top">
            <span
              class="workspace-app-icon"
              :class="`workspace-tone-${workspaceCategory(workspace).id}`"
              ><component
                :is="workspaceIcon(workspace)"
                :size="25"
                :stroke-width="1.8"
                aria-hidden="true"
            /></span>
            <span v-if="workspace.workspaceId === defaultWorkspaceId" class="workspace-default"
              ><Star :size="12" aria-hidden="true" />默认登录空间</span
            >
          </div>
          <div class="workspace-card-body">
            <span class="workspace-card-category">{{ workspaceCategory(workspace).label }}</span>
            <h2>{{ workspace.name }}</h2>
            <p class="workspace-card-description">{{
              workspace.description || "在专属空间中，使用已为你准备好的应用与工具。"
            }}</p>
          </div>
          <footer class="workspace-card-actions">
            <button
              type="button"
              class="workspace-button workspace-button-primary"
              :disabled="loading"
              @click="$emit('switch-workspace', workspace.workspaceId)"
              >进入空间<ArrowUpRight :size="16" aria-hidden="true"
            /></button>
            <button
              type="button"
              class="workspace-button workspace-button-default"
              :disabled="loading || workspace.workspaceId === defaultWorkspaceId"
              :title="
                workspace.workspaceId === defaultWorkspaceId
                  ? '登录后将自动进入此空间'
                  : '设为登录后默认进入的空间'
              "
              @click="$emit('default-workspace', workspace.workspaceId)"
            >
              <component
                :is="workspace.workspaceId === defaultWorkspaceId ? 'Check' : 'Star'"
                :size="15"
                aria-hidden="true"
              />
              {{ workspace.workspaceId === defaultWorkspaceId ? "已设为默认" : "设为我的默认空间" }}
            </button>
          </footer>
        </article>
      </div>
      <div v-else-if="!loading" class="workspace-empty" role="status">
        <span class="workspace-empty-icon"
          ><SearchX v-if="hasFilters" :size="30" aria-hidden="true" /><Layers3
            v-else
            :size="30"
            aria-hidden="true"
        /></span>
        <h3>{{ hasFilters ? "没有找到匹配的空间" : "暂无可用工作空间" }}</h3>
        <p>{{
          hasFilters
            ? "试试其他关键词，或切换分类查看。"
            : "可用空间会显示在这里。如需开通，请联系管理员。"
        }}</p>
        <button
          v-if="hasFilters"
          type="button"
          class="workspace-button workspace-button-secondary"
          @click="resetFilters"
          >清除筛选</button
        >
        <button
          v-else
          type="button"
          class="workspace-button workspace-button-secondary"
          @click="$emit('reload-workspaces')"
          ><RefreshCw :size="15" aria-hidden="true" />重新加载</button
        >
      </div>
      <div v-else class="workspace-empty" role="status"
        ><LoaderCircle :size="28" class="workspace-spinning" aria-hidden="true" /><p
          >正在加载可用空间…</p
        ></div
      >
      <footer class="workspace-pagination-bar">
        <label class="workspace-page-size"
          >每页显示<select v-model.number="pageSize" :disabled="loading" aria-label="每页显示空间数"
            ><option :value="6">6 个</option
            ><option :value="12">12 个</option
            ><option :value="24">24 个</option></select
          ></label
        >
        <AppPagination
          v-if="filteredWorkspaces.length"
          :page="currentPage"
          :page-size="pageSize"
          :total="filteredWorkspaces.length"
          :disabled="loading"
          aria-label="工作空间分页"
          @change="page = $event"
        />
        <span v-else class="workspace-zero-results">共 0 个空间</span>
      </footer>
    </div>
  </section>
</template>

<script src="../js/views/WorkspacesView.js"></script>
<style scoped src="../styles/pages/workspaces.css"></style>
