<template>
  <section class="feature-view domain-skills-page">
    <header class="feature-page-header domain-skills-header">
      <span class="feature-breadcrumb">能力管理 / 数据科学 / 领域技能</span>
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

    <div class="domain-skills-layout">
      <aside class="domain-skill-categories">
        <div class="domain-skill-category-heading">
          <span>分类</span>
          <button v-if="isAdmin" type="button" class="domain-skill-category-add" title="创建分类" aria-label="创建分类" @click="openCategoryDialog">+</button>
        </div>
        <button type="button" class="domain-skill-category-row" :class="{ active: !filters.category }" @click="selectCategory('')">
          <span>全部技能</span><strong>{{ skillCount }}</strong>
        </button>
        <div v-if="categoryMenuId" class="domain-skill-category-action-backdrop" @click="categoryMenuId = ''"></div>
        <div v-for="category in categoryOptions" :key="category.name" class="domain-skill-category-entry" :class="{ active: filters.category === category.name }">
          <button type="button" class="domain-skill-category-row" @click="selectCategory(category.name)">
            <span>{{ category.name }}</span><strong>{{ category.count }}</strong>
          </button>
          <div v-if="isAdmin" class="domain-skill-category-row-actions">
            <button type="button" class="domain-skill-category-actions-trigger" title="分类操作" aria-label="分类操作" :aria-expanded="categoryMenuId === (category.id || category.name)" @click.stop="toggleCategoryMenu(category)">
              <MoreHorizontal :size="16" />
            </button>
            <div v-if="categoryMenuId === (category.id || category.name)" class="domain-skill-category-menu" @click.stop>
              <button type="button" :disabled="busy || !category.count" @click="categoryMenuId = ''; reindexCategory(category)"><RefreshCw :size="14" /><span>重建索引</span></button>
              <button v-if="category.manageable" type="button" @click="openRenameCategory(category)"><Pencil :size="14" /><span>修改分类</span></button>
              <button v-if="category.manageable" type="button" class="danger-action" @click="requestDeleteCategory(category)"><Trash2 :size="14" /><span>删除分类</span></button>
            </div>
          </div>
        </div>
        <p v-if="!categoryOptions.length" class="domain-skill-category-empty">暂无分类，点击分类标题旁的“+”创建。</p>
      </aside>

      <section class="domain-skills-content">
        <div class="domain-skills-summary">
          <span>{{ loading ? '加载中' : `共 ${total} 个技能` }}</span>
          <strong v-if="filters.category">{{ filters.category }}</strong>
        </div>
        <div v-if="loading" class="domain-skills-empty" role="status">正在加载领域技能…</div>
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

    <div v-if="publicationLimitOpen" class="domain-skill-dialog-backdrop" @mousedown.self="closePublicationLimit">
      <section class="domain-skill-dialog publication-limit-dialog" role="dialog" aria-modal="true" aria-labelledby="publication-limit-title">
        <button type="button" class="app-dialog-close publication-limit-close" aria-label="关闭" @click="closePublicationLimit">×</button>
        <div class="publication-limit-icon" aria-hidden="true">!</div>
        <div class="publication-limit-content">
          <p>发布额度提醒</p>
          <h2 id="publication-limit-title">已达到技能发布上限</h2>
          <p>
            当前最多可发布 <strong>{{ publicationLimit.maximum }}</strong> 个领域技能。
            请先回收一个已发布技能，再发布<span v-if="publicationLimit.skillName">“{{ publicationLimit.skillName }}”</span>。
          </p>
          <div class="publication-limit-meter" aria-label="当前发布额度">
            <span>已发布</span>
            <strong>{{ publicationLimit.published }} / {{ publicationLimit.maximum }}</strong>
          </div>
        </div>
        <footer>
          <button type="button" class="secondary-button" @click="closePublicationLimit">我知道了</button>
          <button type="button" @click="viewPublishedSkills">查看已发布技能</button>
        </footer>
      </section>
    </div>

    <div v-if="categoryDialogOpen" class="domain-skill-dialog-backdrop" @mousedown.self="closeCategoryDialog">
      <form class="domain-skill-dialog category-dialog" @submit.prevent="saveCategory">
        <header><div><p>领域技能分类</p><h2>{{ categoryDialogMode === 'rename' ? '重命名分类' : '创建分类' }}</h2></div><button type="button" class="app-dialog-close" aria-label="关闭" :disabled="categorySaving" @click="closeCategoryDialog">×</button></header>
        <p v-if="categoryError" class="domain-skill-dialog-error">{{ categoryError }}</p>
        <label><span>分类名称</span><input ref="categoryNameInput" v-model="newCategoryName" required maxlength="120" placeholder="例如：金融分析、代码规范"></label>
        <footer><button type="button" class="secondary-button" :disabled="categorySaving" @click="closeCategoryDialog">取消</button><button type="submit" :disabled="categorySaving || !newCategoryName.trim()">{{ categorySaving ? '保存中' : categoryDialogMode === 'rename' ? '保存' : '创建' }}</button></footer>
      </form>
    </div>

    <div v-if="editorOpen" class="domain-skill-dialog-backdrop">
      <form class="domain-skill-dialog domain-skills-editor" @input="editorMessage = ''" @submit.prevent="save">
        <header><div><p>领域技能</p><h2>{{ form.id ? '编辑领域技能' : '新建领域技能' }}</h2></div><button type="button" class="app-dialog-close" aria-label="关闭" @click="requestCloseEditor">×</button></header>
        <p v-if="editorMessage" class="domain-skill-dialog-success">{{ editorMessage }}</p>
        <label><span>名称 *</span><input v-model="form.name" required maxlength="200"></label>
        <label><span>分类 *</span><select v-model="form.category" required><option value="" disabled>请选择分类</option><option v-for="item in categoryOptions" :key="item.name" :value="item.name">{{ item.name }}</option></select></label>
        <p v-if="!categoryOptions.length" class="domain-skill-field-hint">暂无可选分类，请使用左侧分类栏的“+”创建分类后再保存。</p>
        <label><span>说明</span><textarea v-model="form.description" rows="2" maxlength="2000"></textarea></label>
        <label class="markdown-field"><span>SKILL.md *</span><textarea v-model="form.markdownContent" required spellcheck="false"></textarea></label>
        <footer><button type="button" class="secondary-button" @click="requestCloseEditor">取消</button><button :disabled="busy">保存草稿</button></footer>
      </form>
    </div>

    <div v-if="importOpen" class="domain-skill-dialog-backdrop">
      <form class="domain-skill-dialog domain-skills-import" @input="importMessage = ''" @submit.prevent="importSkill">
        <header><div><p>领域技能</p><h2>导入技能</h2></div><button type="button" class="app-dialog-close" aria-label="关闭" @click="requestCloseImport">×</button></header>
        <p v-if="importMessage" class="domain-skill-dialog-success">{{ importMessage }}</p>
        <div class="domain-skill-import-modes" role="tablist" aria-label="导入方式">
          <button type="button" role="tab" :aria-selected="importMode === 'file'" :class="{ active: importMode === 'file' }" @click="importMode = 'file'">本地文件</button>
          <button type="button" role="tab" :aria-selected="importMode === 'url'" :class="{ active: importMode === 'url' }" @click="importMode = 'url'">互联网地址</button>
        </div>
        <label><span>名称（可选）</span><input v-model="importName" maxlength="200"></label>
        <label><span>分类 *</span><select v-model="importCategory" required><option value="" disabled>请选择分类</option><option v-for="item in categoryOptions" :key="item.name" :value="item.name">{{ item.name }}</option></select></label>
        <p v-if="!categoryOptions.length" class="domain-skill-field-hint">暂无可选分类，请使用左侧分类栏的“+”创建分类后再导入。</p>
        <label v-if="importMode === 'url'" class="domain-skill-url-field"><span>互联网地址 *</span><input v-model.trim="importUrl" type="url" required maxlength="2048" placeholder="https://example.com/SKILL.md"><small>支持公开的 HTTP/HTTPS Markdown 或 ZIP 地址，最大 5MB。</small></label>
        <label v-else class="file-picker"><input ref="importFileInput" type="file" accept=".zip,.md,.markdown,text/markdown,application/zip" required @change="chooseImport"><strong>{{ importFile?.name || '选择 ZIP 或 Markdown 文件' }}</strong><small>最大 5MB，导入后保存为草稿</small></label>
        <footer><button type="button" class="secondary-button" @click="requestCloseImport">取消</button><button :disabled="busy || !importCategory || (importMode === 'file' ? !importFile : !importUrl.trim())">导入</button></footer>
      </form>
    </div>

    <div v-if="confirmDialog.open" class="domain-skill-confirm-backdrop">
      <section class="domain-skill-confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="domain-skill-confirm-title">
        <div :class="['domain-skill-confirm-icon', { danger: confirmDialog.danger }]" aria-hidden="true">!</div>
        <div class="domain-skill-confirm-copy">
          <h2 id="domain-skill-confirm-title">{{ confirmDialog.title }}</h2>
          <p>{{ confirmDialog.message }}</p>
        </div>
        <footer>
          <button type="button" class="secondary-button" @click="closeConfirmDialog">取消</button>
          <button type="button" :class="{ danger: confirmDialog.danger }" @click="confirmPendingAction">{{ confirmDialog.confirmLabel }}</button>
        </footer>
      </section>
    </div>
  </section>
</template>
<script src="../js/views/DomainSkillsView.js"></script>
<style src="../styles/pages/domain-skills.css"></style>
