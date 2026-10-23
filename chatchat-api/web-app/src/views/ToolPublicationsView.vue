<template>
  <section class="permission-console tool-publications" :aria-busy="busy">
    <header class="permission-page-heading">
      <div
        ><nav aria-label="面包屑">MCP 工具发布 <span>/</span> 模板查询工具</nav><h1>MCP 工具发布</h1
        ><p>选择要包含的模板，设置谁可以使用，发布后供 Agent 选择。</p></div
      >
      <div class="permission-heading-actions"
        ><button type="button" :disabled="busy" @click="refresh">刷新</button
        ><button
          v-if="editable"
          class="permission-primary"
          type="button"
          :disabled="busy"
          @click="reset"
          >新建 MCP 工具</button
        ></div
      >
    </header>
    <p
      v-if="notice"
      class="permission-notice"
      :class="{ error: failed }"
      :role="failed ? 'alert' : 'status'"
      >{{ notice }}</p
    >
    <p v-if="busy" class="publication-loading" role="status">正在处理，请稍候…</p>
    <ol class="publication-workflow" aria-label="MCP 查询工具发布流程"
      ><li><strong>1 · 新建查询工具</strong><span>填写工具名称和用途</span></li
      ><li><strong>2 · 配置子模板集合</strong><span>选择模板来源、使用角色和模板范围</span></li
      ><li><strong>3 · 校验与测试</strong><span>检查配置并预览查询结果</span></li
      ><li><strong>4 · 发布与使用</strong><span>发布后可在 Agent 中选择使用</span></li></ol
    >
    <div class="permission-category-tabs" aria-label="查询工具状态"
      ><button :class="{ active: stateFilter === 'RUNNING' }" @click="stateFilter = 'RUNNING'"
        >运行中的工具</button
      ><button :class="{ active: stateFilter === 'PENDING' }" @click="stateFilter = 'PENDING'"
        >待完成发布（{{ pendingCount }}）</button
      ><button :class="{ active: stateFilter === 'OFFLINE' }" @click="stateFilter = 'OFFLINE'"
        >已下线版本</button
      ></div
    >
    <div class="permission-master-detail">
      <aside class="permission-master">
        <div class="permission-master-head"
          ><strong>工具列表</strong><span>{{ tools.length }} 项</span></div
        >
        <label class="publication-search"
          >业务分类<select v-model="category" aria-label="工具分类"
            ><option value="">全部分类</option
            ><option v-for="name in categories" :key="name" :value="name">{{
              name
            }}</option></select
          ></label
        >
        <label class="publication-search"
          >搜索工具<input v-model="query" type="search" placeholder="搜索名称或说明"
        /></label>
        <label class="publication-search"
          >版本状态<select v-model="stateFilter" aria-label="版本状态"
            ><option value="">全部状态</option
            ><option value="PENDING">待完成发布</option
            ><option v-for="(label, value) in states" :key="value" :value="value">{{
              label
            }}</option></select
          ></label
        >
        <div class="permission-master-list"
          ><button
            v-for="row in visibleTools"
            :key="row.logicalId"
            type="button"
            class="permission-master-item"
            :class="{ active: !editing && selected?.logicalId === row.logicalId }"
            :disabled="busy"
            :aria-pressed="!editing && selected?.logicalId === row.logicalId"
            @click="choose(row)"
            ><strong>{{ row.title }}</strong
            ><small
              >{{ stateFilter ? "匹配" : "最新" }} v{{ row.releaseNumber }} ·
              {{ states[row.state] || "未知状态" }} · 共 {{ versionCount(row) }} 个版本</small
            ></button
          ><p v-if="!tools.length && !busy" class="permission-empty">{{
            query || stateFilter ? "没有匹配的工具，请调整筛选。" : "暂无工具，点击“新建工具”开始。"
          }}</p></div
        >
        <footer class="permission-pagination"
          ><button type="button" :disabled="page <= 1" @click="page--">上一页</button
          ><span>{{ page }} / {{ pages }}</span
          ><button type="button" :disabled="page >= pages" @click="page++">下一页</button></footer
        >
      </aside>
      <form v-if="editing" class="permission-detail publication-editor" @submit.prevent="save">
        <header class="permission-detail-heading"
          ><div
            ><p>{{ form.logicalId ? "版本管理" : "工具定义" }}</p
            ><h2>{{
              editingId
                ? `修改当前草稿 · v${selected.releaseNumber}`
                : form.logicalId
                  ? `为“${selected.title}”创建下一版本`
                  : "新建 MCP 模板查询工具"
            }}</h2
            ><span>{{
              editingId
                ? "保存后仍为当前工具的同一版本，需要重新校验和测试。"
                : "一个工具可以包含多个子模板。先配置可查询集合，再校验、测试并发布。"
            }}</span></div
          ><button type="button" :disabled="busy" @click="cancel">取消</button></header
        >
        <fieldset :disabled="busy || !editable" class="publication-fields"
          ><legend>基本信息</legend
          ><label
            >工具名称<input
              v-model.trim="form.title"
              required
              maxlength="256"
              placeholder="输入业务名称" /></label
          ><label
            >使用说明<textarea
              v-model.trim="form.description"
              rows="3"
              placeholder="说明工具用途与适用场景"
            /></label
          ><label
            >业务分类<input
              v-model.trim="definition.businessCategory"
              maxlength="64"
              list="publication-business-categories"
              placeholder="按业务用途填写，例如客户分析、数据核验" /><datalist
              id="publication-business-categories"
              ><option
                v-for="name in categories.filter((name) => name !== '待分类')"
                :key="name"
                :value="name" /></datalist
          ></label>
          <label
            >工具标识<input
              v-model.trim="definition.domainCode"
              required
              :disabled="identityLocked"
              pattern="[a-z][a-z0-9_]*"
              maxlength="64"
              title="以小写字母开头，只能包含小写字母、数字和下划线"
              placeholder="例如 sales_summary，发布后保持稳定"
            /><small class="publication-hint"
              >以小写字母开头，仅支持小写字母、数字和下划线；同一工具的后续版本沿用此标识。</small
            ></label
          ></fieldset
        >
        <fieldset :disabled="busy || !editable" class="publication-fields"
          ><legend>模板来源与使用范围</legend
          ><div class="permission-form-grid"
            ><label
              >执行能力<select
                v-model="definition.parentToolName"
                aria-label="执行能力"
                required
                :disabled="identityLocked"
                @change="loadTemplates()"
                ><option value="">选择受控模板能力</option
                ><option
                  v-for="parent in parents"
                  :key="parent.toolName"
                  :value="parent.toolName"
                  >{{ parent.title }}</option
                ></select
              ></label
            ><label
              >使用角色<select
                v-model="definition.roleId"
                aria-label="使用角色"
                required
                :disabled="identityLocked"
                @change="loadTemplates()"
                ><option value="">选择已授权角色</option
                ><option v-for="role in roles" :key="role.id" :value="role.id">{{
                  role.roleName
                }}</option></select
              ></label
            ></div
          ><p class="publication-hint"
            >勾选的子模板共同构成此 MCP
            工具的可查询集合。调用时仍校验角色与资源权限，发布不会扩大访问范围。</p
          ></fieldset
        >
        <fieldset :disabled="busy || templatesLoading || !editable" class="publication-fields"
          ><legend
            >可查询的子模板集合 <small>已选 {{ definition.templateKeys.length }} 项</small></legend
          ><div class="publication-template-filters"
            ><label
              >业务分类<select v-model="templateCategory" aria-label="子模板分类"
                ><option value="">全部分类</option
                ><option v-for="name in templateCategories" :key="name" :value="name">{{
                  name
                }}</option></select
              ></label
            >
            <label
              >搜索模板<input
                v-model="templateQuery"
                type="search"
                placeholder="按模板名称筛选" /></label></div
          ><p v-if="templatesLoading" role="status">正在加载已授权模板…</p
          ><p v-else-if="!templates.length" class="permission-empty">{{
            definition.roleId && definition.parentToolName
              ? "当前角色没有可用模板，请先在权限管理中授权。"
              : "选择执行能力和角色后，将显示可用模板。"
          }}</p
          ><div class="publication-template-list"
            ><label
              v-for="item in visibleTemplates"
              :key="item.key"
              class="publication-template-option"
              ><input v-model="definition.templateKeys" type="checkbox" :value="item.key" />{{
                item.title
              }}<small>{{ templateCategoryOf(item) }}</small></label
            ></div
          ><p v-if="templates.length && !filteredTemplates.length" class="publication-hint"
            >没有匹配的子模板，请调整分类或搜索条件。</p
          >
          <div v-if="templates.length" class="publication-template-pagination"
            ><span
              >匹配 {{ filteredTemplates.length }} 项 · 已选
              {{ definition.templateKeys.length }} 项</span
            ><button type="button" :disabled="!visibleTemplates.length" @click="selectTemplatePage"
              >选择本页</button
            ><button
              type="button"
              :disabled="!definition.templateKeys.length"
              @click="clearTemplateSelection"
              >清空已选</button
            ><button type="button" :disabled="templatePage <= 1" @click="templatePage--"
              >上一页</button
            ><span>{{ templatePage }} / {{ templatePages }}</span
            ><button type="button" :disabled="templatePage >= templatePages" @click="templatePage++"
              >下一页</button
            ></div
          >
          <details v-if="selectedTemplates.length" class="publication-selected-templates"
            ><summary>查看已选的 {{ selectedTemplates.length }} 个子模板（含其他分页）</summary
            ><ul
              ><li v-for="item in selectedTemplates" :key="item.key">{{ item.title }}</li></ul
            ></details
          >
          <p v-if="unavailableTemplates.length && !templatesLoading" class="permission-notice error"
            >有 {{ unavailableTemplates.length }} 项原模板当前不可用。<button
              type="button"
              class="permission-link"
              @click="
                definition.templateKeys = definition.templateKeys.filter(
                  (key) => !unavailableTemplates.includes(key)
                )
              "
              >移除不可用模板</button
            ></p
          ></fieldset
        >
        <details class="permission-advanced publication-schema"
          ><summary>高级结构配置</summary
          ><p>仅在需要约束输入输出格式时编辑。结构必须为有效 JSON。</p
          ><label
            >输入结构（Schema）<textarea
              v-model="inputSchema"
              rows="7"
              :disabled="busy"
              spellcheck="false"
            /></label
          ><label
            >输出结构（Schema）<textarea
              v-model="outputSchema"
              rows="7"
              :disabled="busy"
              spellcheck="false"
            /></label
        ></details>
        <footer class="permission-form-actions"
          ><span>{{
            editingId
              ? "修改会清除当前版本旧的测试结果，运行版本与历史快照保留。"
              : "运行版本与历史记录保留，新版本始终属于当前工具。"
          }}</span
          ><button
            class="permission-primary"
            type="submit"
            :disabled="
              busy ||
              templatesLoading ||
              !definition.templateKeys.length ||
              !!unavailableTemplates.length
            "
            >{{ editingId ? "保存当前草稿" : "保存新草稿" }}</button
          ></footer
        >
      </form>
      <section v-else-if="selected" class="permission-detail publication-detail">
        <header class="permission-detail-heading"
          ><div
            ><p>受控工具 · v{{ selected.releaseNumber }}</p
            ><h2>{{ selected.title }}</h2
            ><span>{{ selected.description || "暂未填写使用说明" }}</span></div
          ><button v-if="canEditDraft" type="button" :disabled="busy" @click="editDraft"
            >修改当前草稿</button
          ><button v-else-if="editable" type="button" :disabled="busy" @click="startRevision()"
            >创建新版本</button
          ></header
        >
        <div class="publication-version-bar"
          ><label
            >{{ selected.title }} 的版本<select
              :value="selectedId"
              aria-label="当前版本"
              :disabled="busy"
              @change="chooseVersion"
              ><option v-for="row in versions" :key="row.id" :value="row.id"
                >v{{ row.releaseNumber }} · {{ states[row.state] || "未知状态" }}</option
              ></select
            ></label
          ><span class="permission-badge" :class="{ deny: selected.state === 'OFFLINE' }">{{
            states[selected.state] || "未知状态"
          }}</span></div
        >
        <div class="permission-category-tabs" role="tablist" aria-label="工具详情"
          ><button
            v-for="tab in [
              { id: 'overview', label: '版本概览' },
              { id: 'definition', label: '工具定义' },
              { id: 'testing', label: '测试与记录' },
            ]"
            :id="'publication-tab-' + tab.id"
            :key="tab.id"
            role="tab"
            type="button"
            :aria-selected="activeTab === tab.id"
            :aria-controls="'publication-panel-' + tab.id"
            :class="{ active: activeTab === tab.id }"
            @click="activeTab = tab.id"
            >{{ tab.label }}</button
          ></div
        >
        <section
          :id="'publication-panel-' + activeTab"
          role="tabpanel"
          :aria-labelledby="'publication-tab-' + activeTab"
          class="publication-tab-panel"
        >
          <template v-if="activeTab === 'overview'"
            ><ol class="publication-lifecycle" aria-label="发布流程"
              ><li class="complete">保存草稿</li
              ><li :class="{ complete: selected.state !== 'DRAFT' }">校验定义</li
              ><li :class="{ complete: ['TESTED', 'RUNNING', 'OFFLINE'].includes(selected.state) }"
                >查询测试</li
              ><li :class="{ complete: !!selected.publishedAt }">正式发布</li></ol
            ><dl class="publication-metadata"
              ><div
                ><dt>执行能力</dt><dd>{{ parentName(selectedDefinition.parentToolName) }}</dd></div
              ><div
                ><dt>使用角色</dt><dd>{{ roleName(selectedDefinition.roleId) }}</dd></div
              ><div
                ><dt>绑定模板</dt
                ><dd>{{ selectedDefinition.templateKeys?.length || 0 }} 项</dd></div
              ><div
                ><dt>创建时间</dt><dd>{{ dateLabel(selected.createdAt) }}</dd></div
              ><div
                ><dt>发布时间</dt><dd>{{ dateLabel(selected.publishedAt) }}</dd></div
              ><div
                ><dt>版本状态</dt><dd>{{ states[selected.state] }}</dd></div
              ></dl
            ><p class="publication-hint" role="status">{{
              {
                DRAFT: "下一步：点击“校验定义”，检查子模板集合与参数结构。",
                VALIDATED: "下一步：在“测试与记录”中填写查询参数并运行查询测试。",
                TESTED: "下一步：点击“发布工具”，发布这个子模板查询集合。",
                RUNNING:
                  "查询工具已运行。Agent 可选择此能力，返回范围受已勾选子模板和实时权限约束。",
                OFFLINE: "此版本已下线，保留历史与测试记录。需要恢复时可回滚。",
              }[selected.state]
            }}</p
            ><div class="publication-actions"
              ><button
                v-if="editable && !selected.publishedAt && selected.state === 'DRAFT'"
                type="button"
                class="permission-primary"
                :disabled="busy"
                @click="action(selected, 'validate')"
                >校验定义</button
              ><button
                v-if="testable"
                type="button"
                :disabled="busy"
                @click="activeTab = 'testing'"
                >{{ selected.state === "TESTED" ? "查看并重新测试" : "配置查询测试" }}</button
              ><button
                v-if="publishable && selected.state === 'TESTED'"
                type="button"
                class="permission-primary"
                :disabled="busy"
                @click="action(selected, 'publish')"
                >发布工具</button
              ><button
                v-if="publishable && selected.state === 'RUNNING'"
                type="button"
                class="permission-link danger"
                :disabled="busy"
                @click="confirmAction(selected, 'offline')"
                >下线工具</button
              ><button
                v-if="publishable && selected.state === 'OFFLINE'"
                type="button"
                :disabled="busy"
                @click="confirmAction(selected, 'rollback')"
                >回滚到此版本</button
              ></div
            ></template
          >
          <template v-else-if="activeTab === 'definition'"
            ><p class="publication-hint">{{
              canEditDraft
                ? "此版本尚未发布，可以修改当前草稿；修改后需重新校验和测试。"
                : "已发布版本保留原始快照。需要修改时，在当前工具下创建新版本。"
            }}</p
            ><button v-if="canEditDraft" type="button" :disabled="busy" @click="editDraft"
              >修改当前草稿</button
            ><details class="permission-advanced" open
              ><summary>模板与执行定义</summary
              ><pre>{{ pretty(selected.definitionJson) }}</pre></details
            ><details class="permission-advanced"
              ><summary>输入结构（Schema）</summary
              ><pre>{{ pretty(selected.inputSchemaJson) }}</pre></details
            ><details class="permission-advanced"
              ><summary>输出结构（Schema）</summary
              ><pre>{{ pretty(selected.outputSchemaJson) }}</pre>
            </details></template
          >
          <template v-else
            ><div v-if="testable" class="publication-test-form"
              ><label
                >测试输入<textarea
                  v-model="inputSample"
                  rows="6"
                  spellcheck="false"
                  :disabled="busy"
                  placeholder="输入符合工具结构的 JSON 样例"
                /></label
              ><p class="publication-hint"
                >将真实检索当前角色授权范围内的受控模板，并校验返回结构。</p
              ><button class="permission-primary" type="button" :disabled="busy" @click="test()"
                >运行查询测试</button
              ></div
            ><p v-else class="publication-hint">{{
              selected.state === "DRAFT"
                ? "请先在版本概览中完成定义校验，再运行查询测试。"
                : "已发布的版本保留测试记录；需要重新测试时，请创建新版本。"
            }}</p
            ><div class="publication-evidence"
              ><h3>测试记录</h3
              ><pre v-if="selected.testEvidenceJson">{{ pretty(selected.testEvidenceJson) }}</pre
              ><p v-else class="permission-empty"
                >暂无测试记录，完成定义校验后运行查询测试。</p
              ></div
            ></template
          >
        </section>
        <details class="permission-advanced"
          ><summary>版本技术详情</summary
          ><dl
            ><dt>逻辑标识</dt><dd>{{ selected.logicalId }}</dd
            ><dt>版本标识</dt><dd>{{ selected.id }}</dd
            ><dt>工具标识</dt><dd>{{ selectedDefinition.domainCode }}</dd></dl
          ></details
        >
      </section>
      <section v-else class="permission-detail publication-empty"
        ><h2>{{ busy ? "正在加载工具…" : "开始管理受控工具" }}</h2
        ><p>{{
          editable
            ? "新建工具草稿，依次完成定义校验、查询测试和发布。"
            : "当前没有可查看的工具版本。请联系具备工具发布权限的管理员。"
        }}</p
        ><button
          v-if="editable"
          class="permission-primary"
          type="button"
          :disabled="busy"
          @click="reset"
          >新建 MCP 工具</button
        ></section
      >
    </div>
  </section>
</template>
<script src="../js/views/ToolPublicationsView.js"></script>
