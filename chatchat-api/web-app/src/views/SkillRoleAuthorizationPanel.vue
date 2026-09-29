<template>
  <section class="skill-role-auth">
    <header class="skill-role-auth-head">
      <div>
        <p>授权关系查询</p>
        <h2>技能与角色</h2>
        <small>支持按角色查看全部技能，也支持按技能反查获授权角色。</small>
      </div>
      <button type="button" class="skill-role-refresh" :disabled="loading" @click="reload">
        {{ loading ? "加载中…" : "刷新关系" }}
      </button>
    </header>

    <div class="skill-role-tabs" role="tablist" aria-label="技能角色查询方式">
      <button type="button" :class="{ active: mode === 'role' }" @click="selectMode('role')">按角色查看</button>
      <button type="button" :class="{ active: mode === 'skill' }" @click="selectMode('skill')">按技能查看</button>
    </div>

    <div class="skill-role-toolbar">
      <label v-if="mode === 'role'">
        <span>选择角色</span>
        <select v-model="selectedRoleId">
          <option value="">请选择角色</option>
          <option v-for="role in roles" :key="role.id" :value="role.id">
            {{ role.roleName || role.roleCode }}（{{ role.roleCode }}）
          </option>
        </select>
      </label>
      <label class="skill-role-search">
        <span>模糊搜索技能</span>
        <input v-model.trim="query" type="search" placeholder="输入技能名称、描述、标签，例如：固收" />
      </label>
    </div>

    <div v-if="error" class="skill-role-error">{{ error }}</div>
    <div class="skill-role-summary">{{ summaryText }}</div>

    <div v-if="mode === 'role'" class="skill-role-results">
      <div v-if="loading" class="skill-role-empty">正在加载技能授权关系…</div>
      <div v-else-if="!selectedRoleId" class="skill-role-empty">请选择需要查看的角色</div>
      <div v-else-if="!roleSkills.length" class="skill-role-empty">
        {{ query ? `该角色没有与“${query}”匹配的技能` : "该角色暂未获得技能授权" }}
      </div>
      <div v-else class="skill-role-tree" aria-label="角色到技能的授权关系树">
        <article class="skill-role-node skill-role-root-node role-node">
          <span class="skill-role-node-kind">角色</span>
          <strong>{{ selectedRole.roleName || selectedRole.roleCode }}</strong>
          <p>{{ selectedRole.description || "暂无角色描述" }}</p>
          <small>{{ selectedRole.roleCode }} · {{ selectedRole.id }}</small>
        </article>
        <div class="skill-role-connector" aria-hidden="true">
          <span>关联 {{ roleSkills.length }} 个技能</span>
        </div>
        <div class="skill-role-branches">
          <article v-for="item in roleSkills" :key="`${item.role.id}:${item.skill.key}`" class="skill-role-node skill-node">
            <span class="skill-role-node-kind">{{ item.skill.typeLabel }}</span>
            <strong>{{ item.skill.name }}</strong>
            <p>{{ item.skill.description || "暂无技能描述" }}</p>
            <small>{{ item.skill.id }}</small>
            <div class="skill-role-badges">
              <em v-for="source in item.sources" :key="source">{{ source }}</em>
            </div>
          </article>
        </div>
      </div>
    </div>

    <div v-else class="skill-role-skill-layout">
      <aside class="skill-role-skill-picker" aria-label="匹配技能">
        <header>
          <strong>选择技能节点</strong>
          <span>{{ filteredSkills.length }} 项</span>
        </header>
        <div class="skill-role-skill-list">
          <button
            v-for="skill in filteredSkills"
            :key="skill.key"
            type="button"
            :class="{ active: skill.key === selectedSkillKey }"
            @click="selectedSkillKey = skill.key"
          >
            <strong>{{ skill.name }}</strong>
            <small>{{ skill.typeLabel }} · {{ skill.id }}</small>
          </button>
          <div v-if="!loading && !filteredSkills.length" class="skill-role-empty">
            没有找到与“{{ query }}”相关的技能
          </div>
        </div>
      </aside>
      <section class="skill-role-relation-panel">
        <div v-if="loading" class="skill-role-empty">正在加载技能授权关系…</div>
        <div v-else-if="!selectedSkill" class="skill-role-empty">请从左侧选择技能</div>
        <div v-else-if="!skillRoles.length" class="skill-role-empty">该技能暂未授权给任何角色</div>
        <div v-else class="skill-role-tree skill-to-role-tree" aria-label="技能到角色的授权关系树">
          <article class="skill-role-node skill-role-root-node skill-node">
            <span class="skill-role-node-kind">{{ selectedSkill.typeLabel }}</span>
            <strong>{{ selectedSkill.name }}</strong>
            <p>{{ selectedSkill.description || "暂无技能描述" }}</p>
            <small>{{ selectedSkill.id }}</small>
          </article>
          <div class="skill-role-connector" aria-hidden="true">
            <span>授权给 {{ skillRoles.length }} 个角色</span>
          </div>
          <div class="skill-role-branches">
            <article v-for="item in skillRoles" :key="`${item.skill.key}:${item.role.id}`" class="skill-role-node role-node">
              <span class="skill-role-node-kind">角色</span>
              <strong>{{ item.role.roleName || item.role.roleCode }}</strong>
              <p>{{ item.role.description || "暂无角色描述" }}</p>
              <small>{{ item.role.roleCode }} · {{ item.role.id }}</small>
              <div class="skill-role-badges">
                <em v-for="source in item.sources" :key="source">{{ source }}</em>
              </div>
            </article>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>

<script src="../js/views/SkillRoleAuthorizationPanel.js"></script>
<style src="../styles/pages/skill-role-authorization.css"></style>
