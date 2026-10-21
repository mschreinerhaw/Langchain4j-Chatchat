<template>
  <section class="resource-auth">
    <header class="resource-auth-head">
      <div
        ><p>资源授权</p><h2>角色与 Agent 资源授权</h2
        ><small>选择角色及其绑定的 Agent，分别配置领域技能、文档和 MCP 工具授权。</small></div
      >
    </header>
    <div v-if="notice" class="resource-auth-notice" :class="{ error: failed }">{{ notice }}</div>
    <div class="resource-auth-context">
      <label class="resource-auth-field"
        >当前角色
        <select v-model="roleId" :disabled="!!busyKey">
          <option value="">选择角色</option>
          <option v-for="role in roles" :key="role.id" :value="role.id"
            >{{ role.roleName }}（{{ role.roleCode }}）</option
          >
        </select>
      </label>
    </div>
    <div class="resource-auth-columns">
      <div class="resource-auth-card">
        <h3>{{ allAgentAccess ? "角色可访问的 Agent" : "角色绑定的 Agent" }}</h3>
        <p class="resource-auth-card-description">{{
          allAgentAccess
            ? `“${selectedRoleName}”拥有全部 Agent 访问权限，当前共 ${boundAgentItems.length} 个。`
            : `“${selectedRoleName}”已绑定 ${boundAgentItems.length} 个 Agent。`
        }}</p>
        <div class="resource-auth-tools">
          <input v-model.trim="agentQuery" type="search" placeholder="搜索 Agent 名称或 ID" />
          <button type="button" :disabled="!!busyKey" @click="loadRoleBindings">刷新</button>
        </div>
        <p class="resource-auth-hint">{{
          allAgentAccess
            ? "来源：全部 Agent 访问权限。无需逐个绑定，新增 Agent 自动包含。"
            : "绑定关系在角色管理中维护。"
        }}</p>
        <div class="resource-auth-list" :aria-busy="bindingLoading">
          <div
            v-if="!roleId || bindingLoading || !filteredBoundAgents.length"
            class="resource-auth-list-state"
            role="status"
          >
            {{
              !roleId
                ? "请先选择角色"
                : bindingLoading
                  ? "正在加载…"
                  : boundAgentItems.length
                    ? "没有匹配的 Agent"
                    : "该角色尚未绑定 Agent"
            }}
          </div>
          <button
            v-for="item in filteredBoundAgents"
            :key="item.id"
            type="button"
            class="resource-auth-item resource-auth-agent"
            :class="{ selected: skillId === item.id }"
            :aria-pressed="skillId === item.id"
            :disabled="!!busyKey || bindingLoading"
            @click="skillId = item.id"
          >
            <span
              ><strong>{{ item.name }}</strong
              ><small>{{ item.id }}</small></span
            >
          </button>
        </div>
      </div>
      <div class="resource-auth-card">
        <h3>资源授权</h3>
        <p class="resource-auth-card-description"
          >{{ selectedRoleName }} / {{ selectedAgentName }}</p
        >
        <div class="resource-auth-kinds">
          <button
            v-for="kind in grantKinds"
            :key="kind.value"
            type="button"
            :disabled="!!busyKey"
            :class="{ active: grantKind === kind.value }"
            @click="grantKind = kind.value"
            >{{ kind.label }}</button
          >
        </div>
        <div class="resource-auth-tools">
          <input v-model.trim="query" type="search" placeholder="筛选当前页名称或 ID" />
          <button type="button" :disabled="!!busyKey" @click="reloadResources">刷新</button>
        </div>
        <p class="resource-auth-hint">{{
          inheritedFullAccess
            ? "已继承角色级全部资源授权，无需逐项勾选，新增资源自动包含。本页只读展示生效状态；显式禁止和资源自身访问限制仍然生效。"
            : "勾选后仅授权当前角色使用所选 Agent 时访问该资源；取消勾选将禁止此关系访问该资源。资源自身的访问限制仍然生效。"
        }}</p>
        <div class="resource-auth-list" :aria-busy="loading || grantsLoading">
          <div
            v-if="!roleId || !skillId || loading || grantsLoading || !grantItems.length"
            class="resource-auth-list-state"
            role="status"
          >
            {{
              !roleId
                ? "请先选择角色"
                : !skillId
                  ? "请先从左侧选择已绑定的 Agent"
                  : loading || grantsLoading
                    ? "正在加载…"
                    : "没有匹配的资源"
            }}
          </div>
          <label
            v-for="item in roleId && skillId ? grantItems : []"
            :key="`${grantKind}:${item.id}`"
            class="resource-auth-item resource-auth-resource"
          >
            <input
              type="checkbox"
              :checked="hasGrant(item.id)"
              :disabled="
                !!busyKey || loading || bindingLoading || !grantsLoaded || inheritedFullAccess
              "
              @change="toggleGrant(item.id, $event.target.checked)"
            />
            <span
              ><strong>{{ item.name }}</strong
              ><small>{{ item.id }}</small></span
            >
          </label>
        </div>
        <div
          class="resource-auth-page"
          :class="{ 'resource-auth-reserved': grantKind !== 'KNOWLEDGE' }"
          :aria-hidden="grantKind !== 'KNOWLEDGE'"
        >
          <button
            type="button"
            :disabled="documentPage <= 1 || loading || !!busyKey"
            @click="documentPage--"
            >上一页</button
          >
          <span>{{ documentPage }} / {{ documentPages }}</span>
          <button
            type="button"
            :disabled="documentPage >= documentPages || loading || !!busyKey"
            @click="documentPage++"
            >下一页</button
          >
        </div>
        <p
          class="resource-auth-count"
          :class="{ 'resource-auth-reserved': !skillId || !grantsLoaded }"
          >当前列表已授权 {{ selectedGrantCount }} / {{ grantItems.length }} 项{{ grantKindLabel
          }}{{ inheritedFullAccess ? "（继承全量授权）" : "" }}</p
        >
      </div>
    </div>
  </section>
</template>

<script src="../js/views/ResourceAuthorizationPanel.js"></script>
