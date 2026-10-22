<template>
  <section class="feature-view unified-management"
    ><header
      ><div
        ><h2>工具与数据授权</h2
        ><p>统一维护工具调用和数据范围权限。拒绝规则优先，权限变更在下一次执行时重新检查。</p></div
      ><button :disabled="busy" @click="load">刷新</button></header
    >
    <p v-if="notice" role="status" :class="{ error: failed }">{{ notice }}</p
    ><p v-if="busy" role="status">正在处理…</p>
    <div class="management-columns"
      ><form class="management-card" @submit.prevent="save"
        ><h3>配置授权</h3>
        <label
          >授权对象类型<select v-model="form.targetType" @change="form.targetId = ''"
            ><option value="ROLE">角色</option
            ><option value="USER">用户</option></select
          ></label
        >
        <label
          >授权对象<select v-model="form.targetId" required
            ><option value="">请选择</option
            ><option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{
              subject.roleName || subject.displayName || subject.username
            }}</option></select
          ></label
        >
        <label
          >工具<select v-model="form.localToolName" required
            ><option value="">选择工具</option
            ><option v-for="tool in catalog.tools" :key="tool.id" :value="tool.localToolName">{{
              tool.localToolName
            }}</option></select
          ></label
        >
        <label
          >权限效果<select v-model="form.effect"
            ><option value="allow">允许</option
            ><option value="deny">拒绝</option></select
          ></label
        >
        <label
          >数据范围<input
            v-model.trim="form.scopeExpression"
            maxlength="1000"
            placeholder="mcp:sql_datasource:*:*@domain=数据源ID;level=read"
        /></label>
        <p
          >留空表示该工具的全部数据范围。限定数据源时填写资源范围表达式；工具授权与查询服务、资源授权共同生效。</p
        >
        <label>备注<input v-model.trim="form.remark" maxlength="1000" /></label>
        <button type="submit" :disabled="busy">{{ form.id ? "更新授权" : "保存授权" }}</button
        ><button type="button" @click="reset">清空</button> </form
      ><section class="management-card"
        ><h3>当前授权</h3><p v-if="!catalog.grants?.length">当前租户没有工具授权记录。</p>
        <article v-for="grant in catalog.grants" :key="grant.id" class="release-row"
          ><strong
            >{{ grant.localToolName }} · {{ grant.effect === "deny" ? "拒绝" : "允许" }}</strong
          ><span>{{ subjectName(grant) }}</span
          ><code>{{ grant.scopeExpression || "工具全部范围" }}</code
          ><small>{{ grant.remark }}</small
          ><div class="management-actions"
            ><button :disabled="busy" @click="edit(grant)">编辑</button
            ><button :disabled="busy" @click="remove(grant)">撤销</button></div
          ></article
        >
      </section></div
    >
    <section class="management-card"
      ><h3>最近操作记录</h3><p v-if="!catalog.audits?.length">暂无操作记录。</p
      ><table
        ><thead
          ><tr><th>时间</th><th>操作人</th><th>模块</th><th>操作</th><th>资源</th></tr></thead
        ><tbody
          ><tr v-for="row in catalog.audits" :key="row.id"
            ><td>{{ new Date(row.createdAt).toLocaleString() }}</td
            ><td>{{ row.actorId || "系统" }}</td
            ><td>{{ row.moduleName }}</td
            ><td>{{ row.actionName }}</td
            ><td>{{ row.resourceId }}</td></tr
          ></tbody
        ></table
      ></section
    >
  </section>
</template>
<script src="../js/views/PermissionToolsView.js"></script>
