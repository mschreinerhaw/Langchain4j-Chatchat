<template>
  <section class="permission-resource-editor">
    <header class="permission-detail-heading"
      ><div
        ><p>{{ typeName }} · {{ resource.action }}</p
        ><h2>{{ resource.name }}</h2
        ><span>统一查看角色、用户及租户规则。授权修改会记录审计。</span></div
      ></header
    >
    <p v-if="notice" :class="['permission-notice', { error: failed }]" role="status">{{
      notice
    }}</p>
    <div class="permission-toolbar"
      ><label
        >授权来源<select v-model="source"
          ><option value="">全部来源</option
          ><option value="ROLE">角色直接授权</option
          ><option value="USER">用户直接授权</option
          ><option value="TENANT">租户继承</option></select
        ></label
      ><span>{{ rules.length }} 条规则</span></div
    >
    <div class="permission-table-wrap"
      ><table class="permission-table"
        ><thead
          ><tr
            ><th>授权对象</th><th>动作 / 状态</th><th>授权来源</th><th>生效范围</th
            ><th>操作</th></tr
          ></thead
        ><tbody>
          <tr v-for="rule in filteredRules" :key="rule.id"
            ><td
              ><strong>{{ subjectName(rule) }}</strong
              ><small>{{
                rule.targetType === "USER"
                  ? "用户"
                  : rule.targetType === "ROLE"
                    ? "角色"
                    : "当前租户"
              }}</small></td
            ><td
              ><span
                :class="['permission-badge', { deny: rule.effect.toLowerCase() === 'deny' }]"
                >{{ rule.effect.toLowerCase() === "deny" ? "拒绝" : resource.action }}</span
              ><small>{{ activeGrant(rule) ? "规则启用" : "已停用或过期" }}</small></td
            ><td
              >{{
                rule.targetType === "TENANT"
                  ? "租户继承"
                  : rule.targetType === "ROLE"
                    ? "角色直接授权"
                    : "用户例外"
              }}<small v-if="rule.backend === 'resource' && resource.backend === 'tool'"
                >资源访问约束</small
              ></td
            ><td
              >{{ scopeName(rule)
              }}<details v-if="rule.scopeExpression || rule.agentId"
                ><summary>范围详情</summary
                ><code>{{ rule.scopeExpression || serviceName(rule.agentId) }}</code></details
              ></td
            ><td
              ><button
                v-if="canEdit"
                type="button"
                class="permission-link danger"
                :disabled="busy"
                @click="revoke(rule)"
                >撤销</button
              ><span v-else>只读</span></td
            ></tr
          >
          <tr v-if="!filteredRules.length"
            ><td colspan="5" class="permission-empty"
              >暂无匹配的显式规则。可在下方添加授权；资源原有访问限制仍然生效。</td
            ></tr
          >
        </tbody></table
      ></div
    >
    <details v-if="inheritedUsers.length" class="permission-inheritance"
      ><summary>{{ inheritedUsers.length }} 项用户与角色的授权继承关系</summary
      ><ul
        ><li v-for="entry in inheritedUsers" :key="entry.key"
          >{{ entry.name }} <span>通过 {{ entry.role }}</span></li
        ></ul
      ></details
    >
    <form v-if="canEdit" class="permission-grant-form" @submit.prevent="save">
      <h3>添加授权</h3
      ><div class="permission-form-grid"
        ><label
          >对象类型<select aria-label="对象类型" v-model="form.targetType" @change="form.targetId = ''"
            ><option value="ROLE">角色</option
            ><option v-if="resource.backend !== 'permission'" value="USER">用户</option
            ><option v-if="resource.backend !== 'permission'" value="TENANT"
              >当前租户</option
            ></select
          ></label
        ><label
          >授权对象<select aria-label="授权对象" v-if="form.targetType !== 'TENANT'" v-model="form.targetId" required
            ><option value="">请选择</option
            ><option v-for="item in subjects" :key="item.id" :value="item.id">{{
              item.roleName || item.displayName || item.username
            }}</option></select
          ><input v-else value="当前工作空间所属租户" readonly /></label
        ><label
          >授权动作<select aria-label="授权动作" v-model="form.effect"
            ><option value="allow">允许{{ resource.action }}</option
            ><option v-if="resource.backend !== 'permission'" value="deny"
              >拒绝{{ resource.action }}</option
            ></select
          ></label
        ></div
      >
      <details v-if="resource.backend === 'tool'" class="permission-advanced"
        ><summary>高级范围设置</summary
        ><label
          >数据范围表达式<input
            v-model.trim="form.scopeExpression"
            placeholder="留空使用工具默认资源边界" /></label
        ><p
          >用于保留现有业务资源范围。格式和当前租户由统一权限服务校验，页面筛选不会授予数据访问权限。</p
        ></details
      >
      <footer class="permission-form-actions"
        ><span>保存后立即刷新规则；实际调用仍需通过 Runtime 与资源校验。</span
        ><button class="permission-primary" type="submit" :disabled="busy">{{
          busy ? "保存中…" : "保存授权"
        }}</button></footer
      >
    </form>
    <details class="permission-advanced"
      ><summary>资源技术详情</summary
      ><dl
        ><dt>资源类型</dt><dd>{{ resource.type }}</dd
        ><dt>资源标识</dt
        ><dd
          ><code>{{ resource.code || resource.id }}</code></dd
        ></dl
      ></details
    >
  </section>
</template>
<script src="../js/components/PermissionResourceEditor.js"></script>
