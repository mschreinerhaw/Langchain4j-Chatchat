<template>
  <section class="feature-view domain-skills-page">
    <header class="feature-page-header domain-skills-header">
      <span class="feature-breadcrumb">能力管理 / 数据科学 / 领域技能</span>
      <div v-if="isAdmin" class="feature-page-actions">
        <button type="button" class="feature-button secondary" @click="openMcpSources">MCP Skill 源</button>
        <button type="button" class="feature-button secondary domain-skill-header-import" @click="openImport">
          <span v-if="importTaskRunning" class="domain-skill-header-import-spinner" aria-hidden="true"></span>
          {{ importTaskRunning ? '导入处理中' : '导入 ZIP / MD' }}
        </button>
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
      <button type="button" :disabled="refreshing" @click="load(true)">{{ refreshing ? '检索中…' : '检索' }}</button>
    </section>

    <div class="domain-skills-notice-stack" aria-live="polite" aria-atomic="true">
      <Transition name="domain-skills-notice">
        <p v-if="error" class="domain-skills-notice error" role="alert">
          <span>{{ error }}</span><button type="button" aria-label="关闭错误提示" @click="dismissNotice('error')">×</button>
        </p>
      </Transition>
      <Transition name="domain-skills-notice">
        <p v-if="message" class="domain-skills-notice success" role="status">
          <span>{{ message }}</span><button type="button" aria-label="关闭成功提示" @click="dismissNotice('message')">×</button>
        </p>
      </Transition>
    </div>

    <div class="domain-skills-layout">
      <aside class="domain-skill-categories">
        <div class="domain-skill-category-heading">
          <span>分类</span>
          <button v-if="isAdmin" type="button" class="domain-skill-category-add" title="创建分类" aria-label="创建分类" @click="openCategoryDialog">+</button>
        </div>
        <button type="button" class="domain-skill-category-row" :class="{ active: !filters.category }" @click="selectCategory('')">
          <span>全部技能</span><strong>{{ skillCount }}</strong>
        </button>
        <div v-if="categoryMenuId || skillMenuId" class="domain-skill-category-action-backdrop" @click="categoryMenuId = ''; skillMenuId = ''"></div>
        <div
          v-for="category in categoryOptions"
          :key="category.name"
          class="domain-skill-category-entry"
          :class="{
            active: filters.category === category.name,
            'menu-open': categoryMenuId === (category.id || category.name)
          }"
        >
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

      <section class="domain-skills-content" :aria-busy="refreshing">
        <div class="domain-skills-summary">
          <span>{{ loading ? '加载中' : `共 ${total} 个技能` }}</span>
          <strong v-if="filters.category">{{ filters.category }}</strong>
        </div>
        <div v-if="loading" class="domain-skills-empty" role="status">正在加载领域技能…</div>
        <template v-else>
          <article
            v-for="skill in skills"
            :key="skill.id"
            class="domain-skill-item"
            :class="{ 'menu-open': skillMenuId === skill.id }"
          >
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
              <button type="button" class="domain-skill-item-actions-trigger" title="技能操作" aria-label="技能操作" :aria-expanded="skillMenuId === skill.id" @click.stop="toggleSkillMenu(skill)">
                <MoreHorizontal :size="17" />
              </button>
              <div v-if="skillMenuId === skill.id" class="domain-skill-item-menu" @click.stop>
                <button v-if="!skill.builtin" type="button" @click="skillMenuId = ''; openEdit(skill)"><Pencil :size="14" /><span>编辑</span></button>
                <button v-if="!skill.builtin" type="button" :disabled="busy || (!skill.publicationDirty && skill.status === 'PUBLISHED')" @click="skillMenuId = ''; publishSkill(skill)"><span>{{ skill.status === 'PUBLISHED' ? '重新发布' : '发布' }}</span></button>
                <button v-if="skill.status === 'PUBLISHED'" type="button" :disabled="busy || skill.publicationDirty" :title="skill.publicationDirty ? '存在未发布修改，请先重新发布' : '重建该技能索引'" @click="skillMenuId = ''; reindexSkill(skill)"><RefreshCw :size="14" /><span>重建索引</span></button>
                <button v-if="!skill.builtin && skill.status === 'PUBLISHED'" type="button" :disabled="busy" @click="skillMenuId = ''; recallSkill(skill)"><span>回收</span></button>
                <button v-if="!skill.builtin" type="button" class="danger-action" :disabled="busy" @click="removeSkill(skill)"><Trash2 :size="14" /><span>删除</span></button>
              </div>
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
        <p v-if="error" class="domain-skill-dialog-error">{{ error }}</p>
        <p v-if="importMessage" class="domain-skill-dialog-success">{{ importMessage }}</p>
        <div v-if="importTaskRunning" class="domain-skill-import-running" role="status" aria-live="polite">
          <span class="domain-skill-import-spinner" aria-hidden="true"></span>
          <span><strong>正在后台解析并生成内部 Skill 协议</strong><small>模型处理时间较长属于正常情况，可以关闭弹窗继续使用其他功能。</small></span>
        </div>
        <div class="domain-skill-import-modes" role="tablist" aria-label="导入方式">
          <button type="button" role="tab" :aria-selected="importMode === 'file'" :class="{ active: importMode === 'file' }" @click="importMode = 'file'">本地文件</button>
          <button type="button" role="tab" :aria-selected="importMode === 'url'" :class="{ active: importMode === 'url' }" @click="importMode = 'url'">互联网地址</button>
        </div>
        <label><span>名称（可选）</span><input v-model="importName" maxlength="200"></label>
        <label><span>分类 *</span><select v-model="importCategory" required><option value="" disabled>请选择分类</option><option v-for="item in categoryOptions" :key="item.name" :value="item.name">{{ item.name }}</option></select></label>
        <p v-if="!categoryOptions.length" class="domain-skill-field-hint">暂无可选分类，请使用左侧分类栏的“+”创建分类后再导入。</p>
        <div v-if="importMode === 'url'" class="domain-skill-url-import">
          <label class="domain-skill-url-field"><span>互联网地址 *</span><input v-model.trim="importUrl" type="url" required maxlength="2048" placeholder="https://example.com/SKILL.md"><small>支持互联网或内网 HTTP API 返回的 Markdown / ZIP，最大 5MB。</small></label>
          <button type="button" class="domain-skill-advanced-toggle" :aria-expanded="importAdvancedOpen" aria-controls="domain-skill-http-options" @click="importAdvancedOpen = !importAdvancedOpen">
            <span>高级参数</span><ChevronDown :size="14" :class="{ expanded: importAdvancedOpen }" aria-hidden="true" />
          </button>
          <section v-if="importAdvancedOpen" id="domain-skill-http-options" class="domain-skill-http-options">
            <label><span>请求方法</span><select v-model="importHttpMethod"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option></select></label>
            <label><span>Query 参数（JSON）</span><textarea v-model="importQueryParams" rows="3" spellcheck="false" placeholder='{"version":"latest"}'></textarea></label>
            <label><span>请求头（JSON）</span><textarea v-model="importHeaders" rows="3" spellcheck="false" placeholder='{"Authorization":"Bearer ..."}'></textarea></label>
            <label v-if="importHttpMethod !== 'GET'"><span>请求体</span><textarea v-model="importRequestBody" rows="4" spellcheck="false" placeholder='{"format":"markdown"}'></textarea></label>
            <label class="domain-skill-private-network"><input v-model="importAllowPrivateNetwork" type="checkbox"><span>允许访问内网地址</span></label>
            <small class="domain-skill-http-hint">仅在可信内网接口需要时开启；本机、回环及链路本地地址始终禁止访问。接口响应需为 Markdown 或 ZIP。</small>
          </section>
        </div>
        <label v-else class="file-picker"><input ref="importFileInput" type="file" accept=".zip,.md,.markdown,text/markdown,application/zip" required @change="chooseImport"><strong>{{ importFile?.name || '选择 ZIP 或 Markdown 文件' }}</strong><small>最大 5MB，导入后保存为草稿</small></label>
        <footer><button type="button" class="secondary-button" @click="requestCloseImport">取消</button><button :disabled="busy || importTaskRunning || !importCategory || (importMode === 'file' ? !importFile : !importUrl.trim())"><span v-if="busy || importTaskRunning" class="domain-skill-button-spinner" aria-hidden="true"></span>{{ busy ? '正在提交' : (importTaskRunning ? '后台处理中' : '导入') }}</button></footer>
      </form>
    </div>

    <div v-if="mcpSourcesOpen" class="domain-skill-dialog-backdrop" @mousedown.self="mcpSourcesOpen = false">
      <section class="domain-skill-dialog mcp-sources-dialog" role="dialog" aria-modal="true" aria-labelledby="mcp-sources-title">
        <header>
          <div><p>Skill Federation</p><h2 id="mcp-sources-title">MCP Skill 源</h2></div>
          <button type="button" class="app-dialog-close" aria-label="关闭" @click="mcpSourcesOpen = false">×</button>
        </header>
        <div class="mcp-sources-intro">
          <p>从支持 MCP Skills 扩展的服务发现技能。同步内容先进入草稿，审核发布后才可授权给用户、角色、组织和 Agent。</p>
          <button type="button" @click="openMcpSourceEditor()">新增 Skill 源</button>
        </div>
        <div class="mcp-sources-list">
          <p v-if="mcpSourcesLoading" class="domain-skills-empty">正在加载 Skill 源…</p>
          <p v-else-if="!mcpSources.length" class="domain-skills-empty">暂无 MCP Skill 源</p>
          <template v-else>
          <article v-for="source in mcpSources" :key="source.id" class="mcp-source-card">
            <div>
              <div class="mcp-source-title">
                <strong>{{ source.name }}</strong>
                <span :class="['feature-status', source.status === 'SYNCED' ? 'published' : source.status === 'FAILED' ? 'recalled' : 'draft']">{{ source.status }}</span>
                <span v-if="!source.enabled" class="feature-status draft">已停用</span>
              </div>
              <p>{{ source.endpoint }}</p>
              <small>默认分类：{{ source.defaultCategory }} · 已发现 {{ source.lastDiscoveredCount || 0 }} · 最近同步 {{ formatTime(source.lastSyncedAt) || '从未' }}</small>
              <small v-if="source.lastError" class="mcp-source-error">{{ source.lastError }}</small>
            </div>
            <div class="mcp-source-actions">
              <button type="button" class="secondary-button" @click="openMcpSourceEditor(source)">编辑</button>
              <button type="button" :disabled="mcpSyncingId === source.id || !source.enabled" @click="synchronizeMcpSource(source)">{{ mcpSyncingId === source.id ? '同步中…' : '发现并同步' }}</button>
              <button type="button" class="danger-action" @click="removeMcpSource(source)">删除</button>
            </div>
          </article>
          </template>
        </div>
        <footer><button type="button" class="secondary-button" @click="mcpSourcesOpen = false">关闭</button></footer>
      </section>
    </div>

    <div v-if="mcpSourceEditorOpen" class="domain-skill-dialog-backdrop" @mousedown.self="mcpSourceEditorOpen = false">
      <form class="domain-skill-dialog mcp-source-editor" @submit.prevent="saveMcpSource">
        <header>
          <div><p>MCP Skills 扩展</p><h2>{{ mcpSourceForm.id ? '编辑 Skill 源' : '新增 Skill 源' }}</h2></div>
          <button type="button" class="app-dialog-close" aria-label="关闭" @click="mcpSourceEditorOpen = false">×</button>
        </header>
        <label><span>名称 *</span><input v-model.trim="mcpSourceForm.name" required maxlength="200" placeholder="例如：研究中心 Skills"></label>
        <label><span>MCP Endpoint *</span><input v-model.trim="mcpSourceForm.endpoint" required maxlength="2000" type="url" placeholder="https://example.com/mcp"></label>
        <label><span>Authorization 请求头</span><input v-model="mcpSourceForm.authorization" maxlength="2000" type="password" autocomplete="new-password" placeholder="Bearer …（编辑留空表示不修改）"><small>凭证加密保存，不会在列表和编辑接口回显。</small></label>
        <label><span>同步到分类 *</span><input v-model.trim="mcpSourceForm.defaultCategory" required maxlength="120" placeholder="MCP Skills"></label>
        <label class="mcp-source-check"><input v-model="mcpSourceForm.enabled" type="checkbox"><span>启用该来源</span></label>
        <label class="mcp-source-check"><input v-model="mcpSourceForm.allowPrivateNetwork" type="checkbox"><span>允许连接可信内网地址</span></label>
        <p class="domain-skill-field-hint">本机、回环和链路本地地址始终禁止。远端声明为动态或无法校验 digest 的 Skill 不会导入。</p>
        <footer><button type="button" class="secondary-button" @click="mcpSourceEditorOpen = false">取消</button><button :disabled="mcpSourceSaving">{{ mcpSourceSaving ? '保存中…' : '保存' }}</button></footer>
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
