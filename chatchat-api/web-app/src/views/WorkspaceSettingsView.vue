<template>
  <section class="workspace-settings"
    ><header class="permission-page-heading"
      ><div
        ><nav aria-label="面包屑">平台管理 <span>/</span> 工作空间设置</nav><h1>工作空间设置</h1
        ><p>配置工作空间的名称、入口和启用状态，统一管理默认登录空间。</p></div
      ><button :disabled="busy" @click="load">刷新</button></header
    ><p v-if="notice" :class="['permission-notice', { error: failed }]" role="status">{{
      notice
    }}</p
    ><p v-if="busy && !rows.length" role="status">正在加载工作空间设置…</p>
    <table class="permission-table"
      ><thead
        ><tr><th>工作空间</th><th>状态</th><th>登录设置</th><th>操作</th></tr></thead
      ><tbody
        ><tr v-for="row in rows" :key="row.manifest.workspaceId"
          ><td
            ><strong>{{ row.manifest.name }}</strong
            ><small>{{ row.manifest.description }}</small></td
          ><td>{{ row.status === "enabled" ? "启用" : "停用" }}</td
          ><td>{{ row.defaultWorkspace ? "平台默认空间" : "按用户偏好进入" }}</td
          ><td
            ><div class="permission-heading-actions"
              ><button :disabled="busy" @click="edit(row)">配置</button
              ><button
                v-if="!row.defaultWorkspace && row.status === 'enabled'"
                :disabled="busy"
                @click="setDefault(row)"
                >设为默认</button
              ></div
            ></td
          ></tr
        ></tbody
      ></table
    ><p v-if="!busy && !rows.length" class="permission-empty"
      >当前账号没有可管理的工作空间。请联系平台管理员开通管理权限。</p
    >
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
