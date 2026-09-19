<template>
  <section class="domain-skills-page">
    <header class="domain-skills-header">
      <div><span>能力管理 / 数据科学 / 领域技能</span><h1>领域技能</h1><p>领域技能使用独立索引，发布后可由 Agent 单独关联并在运行时应用。</p></div>
      <div v-if="isAdmin" class="domain-skills-actions"><button class="secondary" @click="importOpen=true">导入 ZIP / MD</button><button class="primary" @click="openCreate">新建技能</button></div>
    </header>
    <p v-if="error" class="domain-skills-alert error">{{ error }}</p><p v-if="message" class="domain-skills-alert success">{{ message }}</p>
    <section class="domain-skills-summary"><div><strong>{{ quotaLabel }}</strong><small>额度来源：{{ quota.source === 'MCP' ? 'MCP License' : '默认策略' }}</small></div><p>未获得 MCP 发布额度时，租户默认最多发布 5 个技能。草稿和已回收技能不占额度。</p></section>
    <form class="domain-skills-toolbar" @submit.prevent="load(true)">
      <input v-model="filters.keyword" type="search" placeholder="检索名称、分类、说明或 Markdown">
      <select v-model="filters.category" @change="load(true)"><option value="">全部分类</option><option v-for="item in categories" :key="item" :value="item">{{ item }}</option></select>
      <select v-model="filters.status" @change="load(true)"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="RECALLED">已回收</option></select>
      <button>检索</button><span>共 {{ total }} 个技能</span>
    </form>
    <div v-if="loading" class="domain-skills-empty">正在加载领域技能…</div>
    <div v-else-if="!skills.length" class="domain-skills-empty"><strong>暂无匹配的领域技能</strong></div>
    <div v-else class="domain-skills-grid">
      <article v-for="skill in skills" :key="skill.id" class="domain-skill-card">
        <header><div><strong>{{ skill.name }}</strong><span class="status">{{ skill.status === 'PUBLISHED' ? '已发布' : skill.status === 'RECALLED' ? '已回收' : '草稿' }}</span><span v-if="skill.publicationDirty" class="status dirty">有未发布修改</span></div><small>{{ skill.category }} · {{ formatTime(skill.updatedAt) }}</small></header>
        <p>{{ skill.description || '暂无说明' }}</p><code>Skill ID：{{ skill.id }}</code>
        <footer v-if="isAdmin"><button @click="openEdit(skill)">编辑</button><button class="publish" :disabled="busy || (!skill.publicationDirty && skill.status==='PUBLISHED')" @click="publishSkill(skill)">{{ skill.status === 'PUBLISHED' ? '重新发布' : '发布' }}</button><button v-if="skill.status==='PUBLISHED'" :disabled="busy" @click="recallSkill(skill)">回收</button><button class="danger" :disabled="busy" @click="removeSkill(skill)">删除</button></footer>
      </article>
    </div>
    <footer v-if="totalPages > 1" class="domain-skills-pagination"><button :disabled="filters.page===0" @click="go(filters.page-1)">上一页</button><span>第 {{ filters.page+1 }} / {{ totalPages }} 页</span><button :disabled="filters.page+1>=totalPages" @click="go(filters.page+1)">下一页</button></footer>

    <div v-if="editorOpen" class="domain-skills-modal" @mousedown.self="!busy&&(editorOpen=false)"><form class="domain-skills-editor" @submit.prevent="save"><header><div><h2>{{ form.id ? '编辑领域技能' : '新建领域技能' }}</h2><p>使用 Markdown 描述适用场景、步骤、约束和输出要求。</p></div><button type="button" @click="editorOpen=false">×</button></header><label>名称 *<input v-model="form.name" required maxlength="200"></label><label>分类 *<input v-model="form.category" required maxlength="120" list="skill-categories"><datalist id="skill-categories"><option v-for="item in categories" :key="item" :value="item" /></datalist></label><label>说明<textarea v-model="form.description" rows="2" maxlength="2000"></textarea></label><label class="markdown-field">SKILL.md *<textarea v-model="form.markdownContent" required spellcheck="false"></textarea></label><footer><button type="button" @click="editorOpen=false">取消</button><button class="primary" :disabled="busy">保存草稿</button></footer></form></div>
    <div v-if="importOpen" class="domain-skills-modal" @mousedown.self="!busy&&(importOpen=false)"><form class="domain-skills-import" @submit.prevent="importSkill"><header><div><h2>导入领域技能</h2><p>支持 .zip、.md、.markdown；ZIP 优先读取 SKILL.md。</p></div><button type="button" @click="importOpen=false">×</button></header><label>名称（可选）<input v-model="importName" maxlength="200"></label><label>分类 *<input v-model="importCategory" required maxlength="120" list="skill-categories"></label><label class="file-picker"><input type="file" accept=".zip,.md,.markdown,text/markdown,application/zip" required @change="chooseImport"><strong>{{ importFile?.name || '选择 ZIP 或 Markdown 文件' }}</strong><small>最大 5MB，导入后保存为草稿</small></label><footer><button type="button" @click="importOpen=false">取消</button><button class="primary" :disabled="busy||!importFile||!importCategory.trim()">导入</button></footer></form></div>
  </section>
</template>
<script src="../js/views/DomainSkillsView.js"></script>
<style src="../styles/pages/domain-skills.css"></style>
