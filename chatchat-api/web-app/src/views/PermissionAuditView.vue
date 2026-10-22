<template>
  <section class="permission-console permission-audit-page"
    ><header class="permission-page-heading"
      ><div
        ><nav aria-label="面包屑">权限管理 <span>/</span> 安全审计</nav><h1>安全审计</h1
        ><p>查询账户登录行为与授权变更，追踪操作人员、对象和结果。</p></div
      ></header
    ><nav class="permission-category-tabs" aria-label="审计类型"
      ><button :class="{ active: mode === 'login' }" @click="mode = 'login'">登录行为</button
      ><button
        :class="{ active: mode === 'authorization' }"
        @click="
          mode = 'authorization';
          load();
        "
        >授权变更</button
      ></nav
    >
    <SystemManagementView v-if="mode === 'login'" section="logins" embedded />
    <template v-else
      ><p v-if="notice" class="permission-notice error" role="alert">{{ notice }}</p
      ><form
        class="permission-toolbar"
        @submit.prevent="
          page = 1;
          load();
        "
        ><label class="permission-search"
          >搜索操作人或变更内容<input
            v-model="query"
            type="search"
            placeholder="输入名称或变更内容" /></label
        ><label
          >操作<select v-model="action"
                  ><option value="">全部操作</option><option value="rejected">拒绝或失败</option
            ><option value="role-authorization">角色授权</option
            ><option value="save">工具授权</option
            ><option value="delete">撤销工具授权</option
            ><option value="grant">资源授权</option
            ><option value="update-grant">更新资源授权</option
            ><option value="revoke">撤销资源授权</option></select
          ></label
        ><button :disabled="busy">{{ busy ? "查询中…" : "查询" }}</button></form
      ><div class="permission-table-wrap"
        ><table class="permission-table"
          ><thead
            ><tr
              ><th>时间</th><th>操作人</th><th>操作</th><th>资源类型</th><th>结果</th
              ><th>详情</th></tr
            ></thead
          ><tbody
            ><tr v-for="row in result.items" :key="row.id"
              ><td>{{ new Date(row.createdAt).toLocaleString("zh-CN") }}</td
              ><td>{{ row.actor }}</td
              ><td>{{ actionNames[row.action] || "授权变更" }}</td
              ><td>{{ typeNames[row.resourceType] || "系统资源" }}</td
              ><td>{{ row.result === "success" ? "成功" : "失败" }}</td
              ><td
                ><details
                  ><summary>查看记录</summary
                  ><code>{{ row.detail }}<br />{{ row.resourceId }}</code></details
                ></td
              ></tr
            ><tr v-if="!result.items.length"
              ><td colspan="6" class="permission-empty"
                >暂无匹配的授权变更。角色、资源及工具授权操作会在这里记录。</td
              ></tr
            ></tbody
          ></table
        ></div
      ><footer class="permission-pagination"
        ><span>共 {{ result.total }} 条</span
        ><button
          :disabled="busy || page <= 1"
          @click="
            page--;
            load();
          "
          >上一页</button
        ><span>{{ page }} / {{ Math.max(1, result.pages) }}</span
        ><button
          :disabled="busy || page >= result.pages"
          @click="
            page++;
            load();
          "
          >下一页</button
        ></footer
      ></template
    >
  </section>
</template>
<script src="../js/views/PermissionAuditView.js"></script>
