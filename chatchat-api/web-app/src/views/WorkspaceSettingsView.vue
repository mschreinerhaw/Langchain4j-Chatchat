<template>
  <section class="workspace-settings"
    ><header class="permission-page-heading"
      ><div
        ><nav aria-label="面包屑">平台管理 <span>/</span> 数据空间</nav><h1>数据空间</h1
        ><p>配置工作空间的名称、入口和启用状态，统一管理默认登录空间。</p></div
      ><button type="button" class="workspace-refresh" :disabled="busy" @click="load"
        ><RefreshCw :size="16" :class="{ spinning: busy }" aria-hidden="true" />{{
          busy ? "刷新中…" : "刷新"
        }}</button
      ></header
    ><p v-if="notice" :class="['permission-notice', { error: failed }]" role="status">{{
      notice
    }}</p
    ><p v-if="busy && !rows.length" role="status">正在加载数据空间…</p>
    <section class="workspace-catalog" aria-label="工作空间目录" :aria-busy="busy">
      <div class="workspace-catalog-toolbar">
        <label class="workspace-search"
          ><span>检索工作空间</span
          ><div
            ><Search :size="18" aria-hidden="true" /><input
              v-model="searchQuery"
              type="search"
              placeholder="搜索工作空间名称、说明或标识" /></div
        ></label>
        <label class="workspace-status-filter"
          ><span>启用状态</span
          ><select v-model="statusFilter"
            ><option value="all">全部状态</option
            ><option value="enabled">已启用</option
            ><option value="disabled">已停用</option></select
          ></label
        >
        <p class="workspace-result-count" role="status"
          >共 {{ rows.length }} 个空间，找到 {{ filteredRows.length }} 个</p
        >
      </div>
      <div v-if="pagedRows.length" class="workspace-settings-grid">
        <article
          v-for="row in pagedRows"
          :key="row.manifest.workspaceId"
          class="workspace-settings-card"
        >
          <header
            ><span class="workspace-card-icon"><Layers3 :size="22" aria-hidden="true" /></span
            ><div class="workspace-card-badges"
              ><span :class="['workspace-status', { enabled: row.status === 'enabled' }]">{{
                row.status === "enabled" ? "已启用" : "已停用"
              }}</span
              ><span v-if="row.defaultWorkspace" class="workspace-default">平台默认</span></div
            ></header
          >
          <h2>{{ row.manifest.name }}</h2
          ><p class="workspace-card-description">{{
            row.manifest.description || "暂无空间说明"
          }}</p>
          <dl
            ><div
              ><dt>默认入口</dt
              ><dd>{{ pageLabels[row.manifest.entryPage] || row.manifest.entryPage }}</dd></div
            ><div
              ><dt>登录设置</dt
              ><dd>{{ row.defaultWorkspace ? "平台默认空间" : "按用户偏好进入" }}</dd></div
            ></dl
          >
          <footer
            ><div class="workspace-card-secondary"
              ><button type="button" :disabled="busy" @click="edit(row)"
                ><Settings2 :size="15" aria-hidden="true" />配置</button
              ><button
                v-if="!row.defaultWorkspace && row.status === 'enabled'"
                type="button"
                :disabled="busy"
                @click="setDefault(row)"
                >设为默认</button
              ></div
            ><button
              type="button"
              class="permission-primary workspace-enter"
              :disabled="busy || row.status !== 'enabled'"
              @click="enter(row)"
              >进入工作空间<ArrowRight :size="16" aria-hidden="true" /></button
          ></footer>
        </article>
      </div>
      <p v-else-if="!busy" class="permission-empty">{{
        rows.length
          ? "没有匹配的工作空间，请调整关键词或状态。"
          : "当前账号没有可管理的工作空间。请联系平台管理员开通管理权限。"
      }}</p>
      <AppPagination
        :page="page"
        :page-size="pageSize"
        :total="filteredRows.length"
        :page-count="pageCount"
        :disabled="busy"
        aria-label="工作空间分页"
        @change="setPage"
      />
    </section>
    <Teleport to="body"
      ><div v-if="draft" class="permission-modal-backdrop"
        ><form class="permission-profile-dialog" @submit.prevent="save"
          ><header
            ><h2>配置工作空间</h2
            ><button type="button" :disabled="busy" aria-label="关闭设置" @click="draft = null"
              >×</button
            ></header
          ><p v-if="formError" class="permission-notice error" role="alert">{{ formError }}</p
          ><label>名称<input v-model.trim="draft.manifest.name" required /></label
          ><label>说明<textarea v-model.trim="draft.manifest.description" rows="3" /></label
          ><div class="permission-form-grid"
            ><label
              >启用状态<select v-model="draft.status"
                ><option value="enabled">启用</option
                ><option value="disabled">停用</option></select
              ></label
            ><label
              >默认入口<select v-model="draft.manifest.entryPage"
                ><option v-for="page in draft.manifest.pages" :key="page.id" :value="page.id">{{
                  pageLabels[page.id] || page.id
                }}</option></select
              ></label
            ></div
          ><details
            ><summary>高级配置</summary
            ><label>访问权限编码<input v-model.trim="draft.accessPermissionCode" required /></label
            ><p>仅填写已有权限编码；此处不会为用户增加访问授权。</p
            ><dl
              ><dt>配置版本</dt><dd>{{ draft.manifest.configVersion }}</dd
              ><dt>工作空间标识</dt><dd>{{ draft.manifest.workspaceId }}</dd></dl
            ></details
          ><footer
            ><button type="button" :disabled="busy" @click="draft = null">取消</button
            ><button class="permission-primary" :disabled="busy">{{
              busy ? "保存中…" : "保存配置"
            }}</button></footer
          ></form
        ></div
      ></Teleport
    >
  </section>
</template>
<script src="../js/views/WorkspaceSettingsView.js"></script>
