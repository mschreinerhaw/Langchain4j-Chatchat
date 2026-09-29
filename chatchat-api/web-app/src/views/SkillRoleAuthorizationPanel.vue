<template>
  <section class="skill-role-auth">
    <header class="skill-role-auth-head">
      <div><p>授权关系查询</p><h2>技能权限</h2><small>按角色或技能双向查询有效授权，明细区分公共授权与指定 Agent 授权。</small></div>
      <button type="button" :disabled="busy" @click="reload">刷新</button>
    </header>
    <div class="skill-role-controls">
      <div class="skill-role-tabs" role="tablist" aria-label="查询方式">
        <button type="button" role="tab" :aria-selected="mode === 'role'" :class="{ active: mode === 'role' }" @click="selectMode('role')">按角色查看</button>
        <button type="button" role="tab" :aria-selected="mode === 'skill'" :class="{ active: mode === 'skill' }" @click="selectMode('skill')">按技能查看</button>
      </div>
      <label>能力类型 <select v-model="resourceType"><option value="">全部类型</option><option value="AGENT_SKILL">Agent</option><option value="SKILL">领域技能</option></select></label>
      <label>每页 <select v-model.number="pageSize" @change="resize"><option :value="20">20 条</option><option :value="50">50 条</option><option :value="100">100 条</option></select></label>
    </div>
    <div class="skill-role-workspace">
      <aside class="skill-role-picker" :aria-busy="catalogLoading">
        <header><strong>{{ mode === 'role' ? '角色列表' : '技能列表' }}</strong><small>共 {{ catalog.total }} 项</small></header>
        <input v-model.trim="query" maxlength="200" type="search" :aria-label="mode === 'role' ? '搜索角色' : '搜索技能'" :placeholder="mode === 'role' ? '搜索角色名称、编码' : '搜索名称、描述、标签，如：固收'" />
        <div class="skill-role-picker-body">
          <div v-if="catalogError" class="skill-role-empty error" role="alert">{{ catalogError }} <button @click="loadCatalog">重试</button></div>
          <div v-else-if="catalogLoading" class="skill-role-empty">正在加载…</div>
          <div v-else-if="!catalog.items.length" class="skill-role-empty">没有匹配的{{ mode === 'role' ? '角色' : '技能' }}</div>
          <button v-for="item in catalog.items" :key="key(item)" type="button" class="skill-role-picker-item" :class="{ active: selected && key(item) === key(selected) }" :aria-pressed="!!selected && key(item) === key(selected)" @click="select(item)">
            <strong :title="item.name">{{ item.name }}</strong>
            <small :title="item.code || item.id">{{ mode === 'role' ? item.code : typeLabel(item.resource_type) }}</small>
          </button>
        </div>
        <footer class="skill-role-pagination">
          <button :disabled="catalogLoading || catalogPage <= 1" @click="changePage('catalog', catalogPage - 1)">上一页</button>
          <label><input type="number" min="1" :max="catalog.totalPages" :value="catalogPage" :disabled="catalogLoading" aria-label="跳转对象列表页码" @change="changePage('catalog', $event.target.value)" /> / {{ catalog.totalPages }}</label>
          <button :disabled="catalogLoading || catalogPage >= catalog.totalPages" @click="changePage('catalog', catalogPage + 1)">下一页</button>
        </footer>
      </aside>
      <section class="skill-role-detail" :aria-busy="relationLoading">
        <header>
          <div><h3 :title="selectedName">{{ selectedName || '授权明细' }}</h3><small>共 {{ relations.total }} 条有效授权关系</small></div>
          <input v-model.trim="relationQuery" maxlength="200" type="search" :aria-label="mode === 'role' ? '搜索授权技能' : '搜索获授权角色'" :placeholder="mode === 'role' ? '搜索授权技能名称、描述' : '搜索获授权角色名称、编码'" />
        </header>
        <p class="skill-role-note">同一技能在不同 Agent 下的授权分别展示。此处展示角色授权记录，不等同于某个用户运行时的最终权限。</p>
        <div class="skill-role-table-wrap">
          <div v-if="relationError" class="skill-role-empty error" role="alert">{{ relationError }} <button @click="loadRelations">重试</button></div>
          <div v-else-if="relationLoading" class="skill-role-empty">正在加载授权明细…</div>
          <div v-else-if="!selected" class="skill-role-empty">请从左侧选择{{ mode === 'role' ? '角色' : '技能' }}</div>
          <div v-else-if="!relations.items.length" class="skill-role-empty">没有匹配的有效授权关系</div>
          <table v-else class="skill-role-table">
            <thead><tr><th>{{ mode === 'role' ? '技能 / Agent' : '角色' }}</th><th>类型</th><th>授权范围</th><th>来源</th></tr></thead>
            <tbody><tr v-for="row in relations.items" :key="[row.role_id, row.resource_type, row.skill_id, row.agent_id, row.source].join(':')">
              <td><strong :title="mode === 'role' ? row.skill_name : row.role_name">{{ mode === 'role' ? row.skill_name : row.role_name }}</strong><small :title="mode === 'role' ? row.skill_id : row.role_code">{{ mode === 'role' ? row.skill_id : row.role_code }}</small></td>
              <td>{{ typeLabel(row.resource_type) }}</td>
              <td><span :title="row.agent_id ? row.agent_name || row.agent_id : '该角色的公共授权'">{{ row.agent_id ? row.agent_name || row.agent_id : '角色公共范围' }}</span></td>
              <td><span class="skill-role-source">{{ row.source }}</span></td>
            </tr></tbody>
          </table>
        </div>
        <footer class="skill-role-pagination">
          <span>共 {{ relations.total }} 条</span>
          <button :disabled="relationLoading || relationPage <= 1" @click="changePage('relations', relationPage - 1)">上一页</button>
          <label><input type="number" min="1" :max="relations.totalPages" :value="relationPage" :disabled="relationLoading" aria-label="跳转授权明细页码" @change="changePage('relations', $event.target.value)" /> / {{ relations.totalPages }}</label>
          <button :disabled="relationLoading || relationPage >= relations.totalPages" @click="changePage('relations', relationPage + 1)">下一页</button>
        </footer>
      </section>
    </div>
  </section>
</template>
<script src="../js/views/SkillRoleAuthorizationPanel.js"></script>
<style src="../styles/pages/skill-role-authorization.css"></style>
