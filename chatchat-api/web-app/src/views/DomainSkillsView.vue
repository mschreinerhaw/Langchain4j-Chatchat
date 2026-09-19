<template>
  <section class="feature-view domain-skills-page">
    <header class="feature-page-header domain-skills-header">
      <div class="feature-page-heading">
        <span class="feature-breadcrumb">能力管理 / 数据科学 / 领域技能</span>
        <h1>领域技能</h1>
        <p>管理可由 Agent 独立关联的专业知识、规则和工作流程。</p>
      </div>
      <div v-if="isAdmin" class="feature-page-actions">
        <button type="button" class="feature-button secondary" @click="openImport">导入 ZIP / MD</button>
        <button type="button" class="feature-button primary" @click="openCreate">新建技能</button>
      </div>
    </header>

    <section class="domain-skills-toolbar">
      <label class="domain-skills-search-field">
        <span>技能检索</span>
        <input v-model="filters.keyword" type="search" placeholder="按名称、说明或 Markdown 检索" @keyup.enter="load(true)">
      </label>
      <label>
        <span>状态</span>
        <select v-model="filters.status" @change="load(true)">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
          <option value="RECALLED">已回收</option>
        </select>
      </label>
      <button type="button" @click="load(true)">检索</button>
    </section>

    <p v-if="error" class="domain-skills-notice error">{{ error }}</p>
    <p v-if="message" class="domain-skills-notice success">{{ message }}</p>

    <section class="domain-skills-quota">
      <div><strong>{{ quotaLabel }}</strong><span>额度来源：{{ quota.source === 'MCP' ? 'MCP License' : '默认策略' }}</span></div>
      <p>草稿和已回收技能不占发布额度。</p>
    </section>

    <div class="domain-skills-layout">
      <aside class="domain-skill-categories">
        <div class="domain-skill-category-heading">
          <span>分类</span>
          <button v-if="isAdmin" type="button" class="domain-skill-category-add" title="创建分类" aria-label="创建分类" @click="openCategoryDialog">+</button>
        </div>
        <button type="button" class="domain-skill-category-row" :class="{ active: !filters.category }" @click="selectCategory('')">
          <span>全部技能</span><strong>{{ skillCount }}</strong>
        </button>
        <div v-for="category in categoryOptions" :key="category.name" class="domain-skill-category-entry">
          <button type="button" class="domain-skill-category-row" :class="{ active: filters.category === category.name }" @click="selectCategory(category.name)">
            <span>{{ category.name }}</span><strong>{{ category.count }}</strong>
          </button>
          <button v-if="isAdmin" type="button" class="domain-skill-category-reindex" :disabled="busy || !category.count" title="重建该分类下已发布技能的索引" :aria-label="`重建${category.name}分类索引`" @click="reindexCategory(category)">↻</button>
        </div>
        <p v-if="!categoryOptions.length" class="domain-skill-category-empty">暂无分类，点击分类标题旁的“+”创建。</p>
      </aside>

      <section class="domain-skills-content">
        <div class="domain-skills-summary">
          <span>{{ loading ? '加载中' : `共 ${total} 个技能` }}</span>
          <strong v-if="filters.category">{{ filters.category }}</strong>
        </div>
        <div v-if="loading" class="domain-skills-empty" role="status">正在加载领域技能…</div>
        <div v-else-if="!skills.length" class="domain-skills-empty">
          <strong>暂无领域技能</strong>
          <p>{{ filters.category ? '该分类下暂无技能，可以新建或导入技能。' : '当前筛选条件下暂无技能，可以新建或导入技能。' }}</p>
          <button v-if="isAdmin" type="button" @click="openCreate">新建技能</button>
        </div>
        <template v-else>
          <article v-for="skill in skills" :key="skill.id" class="domain-skill-item">
            <div class="domain-skill-item-body">
              <div class="domain-skill-item-title">
                <strong>{{ skill.name }}</strong>
                <div class="domain-skill-statuses">
                  <span v-if="skill.builtin" class="feature-status builtin">系统内置</span>
                  <span :class="['feature-status', String(skill.status || 'DRAFT').toLowerCase()]">{{ skill.status === 'PUBLISHED' ? '已发布' : skill.status === 'RECALLED' ? '已回收' : '草稿' }}</span>
                  <span v-if="skill.publicationDirty" class="feature-status warning">有未发布修改</span>
                </div>
              </div>
              <p>{{ skill.description || '暂无说明' }}</p>
              <span>{{ skill.category }} · {{ formatTime(skill.updatedAt) }} · {{ skill.id }}</span>
            </div>
            <div v-if="isAdmin" class="domain-skill-item-actions">
              <button v-if="!skill.builtin" type="button" @click="openEdit(skill)">编辑</button>
              <button v-if="!skill.builtin" type="button" :disabled="busy || (!skill.publicationDirty && skill.status === 'PUBLISHED')" @click="publishSkill(skill)">{{ skill.status === 'PUBLISHED' ? '重新发布' : '发布' }}</button>
              <button v-if="skill.status === 'PUBLISHED'" type="button" :disabled="busy || skill.publicationDirty" :title="skill.publicationDirty ? '存在未发布修改，请先重新发布' : '重建该技能索引'" @click="reindexSkill(skill)">重建索引</button>
              <button v-if="!skill.builtin && skill.status === 'PUBLISHED'" type="button" :disabled="busy" @click="recallSkill(skill)">回收</button>
              <button v-if="!skill.builtin" type="button" class="danger-action" :disabled="busy" @click="removeSkill(skill)">删除</button>
            </div>
          </article>
        </template>

        <footer v-if="totalPages > 1" class="domain-skills-pagination">
          <span>第 {{ filters.page + 1 }} / {{ totalPages }} 页</span>
          <div><button type="button" :disabled="filters.page === 0" @click="go(filters.page - 1)">上一页</button><button type="button" :disabled="filters.page + 1 >= totalPages" @click="go(filters.page + 1)">下一页</button></div>
        </footer>
      </section>
    </div>

    <div v-if="categoryDialogOpen" class="domain-skill-dialog-backdrop" @mousedown.self="closeCategoryDialog">
      <form class="domain-skill-dialog category-dialog" @submit.prevent="saveCategory">
        <header><div><p>领域技能分类</p><h2>创建分类</h2></div><button type="button" class="app-dialog-close" aria-label="关闭" :disabled="categorySaving" @click="closeCategoryDialog">×</button></header>
        <p v-if="categoryError" class="domain-skill-dialog-error">{{ categoryError }}</p>
        <label><span>分类名称</span><input ref="categoryNameInput" v-model="newCategoryName" required maxlength="120" placeholder="例如：金融分析、代码规范"></label>
        <footer><button type="button" class="secondary-button" :disabled="categorySaving" @click="closeCategoryDialog">取消</button><button type="submit" :disabled="categorySaving || !newCategoryName.trim()">{{ categorySaving ? '创建中' : '创建' }}</button></footer>
      </form>
    </div>

    <div v-if="editorOpen" class="domain-skill-dialog-backdrop" @mousedown.self="!busy && (editorOpen = false)">
      <form class="domain-skill-dialog domain-skills-editor" @submit.prevent="save">
        <header><div><p>领域技能</p><h2>{{ form.id ? '编辑领域技能' : '新建领域技能' }}</h2></div><button type="button" class="app-dialog-close" aria-label="关闭" @click="editorOpen = false">×</button></header>
        <label><span>名称 *</span><input v-model="form.name" required maxlength="200"></label>
        <label><span>分类 *</span><select v-model="form.category" required><option value="" disabled>请选择分类</option><option v-for="item in categoryOptions" :key="item.name" :value="item.name">{{ item.name }}</option></select></label>
        <p v-if="!categoryOptions.length" class="domain-skill-field-hint">暂无可选分类，请使用左侧分类栏的“+”创建分类后再保存。</p>
        <label><span>说明</span><textarea v-model="form.description" rows="2" maxlength="2000"></textarea></label>
        <label class="markdown-field"><span>SKILL.md *</span><textarea v-model="form.markdownContent" required spellcheck="false"></textarea></label>
        <footer><button type="button" class="secondary-button" @click="editorOpen = false">取消</button><button :disabled="busy">保存草稿</button></footer>
      </form>
    </div>

    <div v-if="importOpen" class="domain-skill-dialog-backdrop" @mousedown.self="!busy && (importOpen = false)">
      <form class="domain-skill-dialog domain-skills-import" @submit.prevent="importSkill">
        <header><div><p>领域技能</p><h2>导入技能</h2></div><button type="button" class="app-dialog-close" aria-label="关闭" @click="importOpen = false">×</button></header>
        <label><span>名称（可选）</span><input v-model="importName" maxlength="200"></label>
        <label><span>分类 *</span><select v-model="importCategory" required><option value="" disabled>请选择分类</option><option v-for="item in categoryOptions" :key="item.name" :value="item.name">{{ item.name }}</option></select></label>
        <p v-if="!categoryOptions.length" class="domain-skill-field-hint">暂无可选分类，请使用左侧分类栏的“+”创建分类后再导入。</p>
        <label class="file-picker"><input type="file" accept=".zip,.md,.markdown,text/markdown,application/zip" required @change="chooseImport"><strong>{{ importFile?.name || '选择 ZIP 或 Markdown 文件' }}</strong><small>最大 5MB，导入后保存为草稿</small></label>
        <footer><button type="button" class="secondary-button" @click="importOpen = false">取消</button><button :disabled="busy || !importFile || !importCategory">导入</button></footer>
      </form>
    </div>
  </section>
</template>
<script src="../js/views/DomainSkillsView.js"></script>
<style src="../styles/pages/domain-skills.css"></style>
